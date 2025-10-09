# CLAUDE.md - Dual-Agent Orchestrator Project

## Project Overview

**Dual-Agent Orchestrator** is a Kotlin-based MCP (Model Context Protocol) server that enables intelligent coordination between multiple AI coding agents (Claude Code, Codex CLI, and future models like Gemini, Qwen, etc.) for token-optimized, consensus-driven software development.

### Core Concept

Instead of using a single AI agent for all tasks, this orchestrator:
- Routes tasks to the most suitable agent(s) based on complexity, risk, and capabilities
- Enables consensus workflows where multiple agents review critical decisions
- Optimizes token usage by leveraging each agent's strengths
- Provides conversation-based handoff between agents
- Supports extensible plugin architecture for adding new AI models

### Architecture Pattern

```
User → Agent (Claude Code/Codex CLI) → Orchestrator MCP Server → Coordinates both agents
```

The orchestrator runs as an **HTTP MCP server** that both agents connect to as **MCP clients**.

---

## Technology Stack

### Core Technologies
- **Language**: Kotlin 2.0+ (JVM)
- **Build**: Gradle Kotlin DSL
- **Concurrency**: Kotlin Coroutines & Flows
- **Serialization**: kotlinx.serialization

### Key Libraries
- **MCP**: `io.github.modelcontextprotocol:kotlin-sdk` (MCP server implementation)
- **HTTP Server**: Ktor (for MCP HTTP transport)
- **Database**: DuckDB JDBC driver (analytical queries, no connection pooling)
- **Database Access**: Raw JDBC (no ORM - manual PreparedStatements)

### Why These Choices?
- **DuckDB**: Optimized for analytical queries (metrics, token usage analysis, pattern learning)
- **Raw JDBC**: Full control, transparency, no ORM magic, minimal dependencies
- **HTTP MCP**: Both Claude Code and Codex CLI support HTTP transport natively
- **Single connection**: No pooling needed for single-user desktop application

---

## Project Structure

```
dual-agent-orchestrator/
│
├── build.gradle.kts
├── settings.gradle.kts
├── CLAUDE.md (this file)
│
├── config/
│   ├── agents.toml          # Agent configurations
│   └── application.conf     # Server configuration
│
├── src/
│   ├── main/
│   │   ├── kotlin/
│   │   │   └── com/orchestrator/
│   │   │       │
│   │   │       ├── Main.kt
│   │   │       │
│   │   │       ├── core/
│   │   │       │   ├── AgentFactory.kt           # Factory interface for agents
│   │   │       │   ├── AgentFactoryRegistry.kt   # SPI-based factory registry
│   │   │       │   ├── AgentRegistry.kt          # Active agent registry
│   │   │       │   ├── OrchestrationEngine.kt    # Main coordinator
│   │   │       │   ├── EventBus.kt               # In-process event system
│   │   │       │   └── StateMachine.kt           # Workflow state machine
│   │   │       │
│   │   │       ├── modules/
│   │   │       │   ├── routing/                  # Task routing logic
│   │   │       │   │   ├── RoutingModule.kt
│   │   │       │   │   ├── TaskClassifier.kt
│   │   │       │   │   ├── AgentSelector.kt
│   │   │       │   │   └── StrategyPicker.kt
│   │   │       │   │
│   │   │       │   ├── consensus/                # Consensus management
│   │   │       │   │   ├── ConsensusModule.kt
│   │   │       │   │   ├── ProposalManager.kt
│   │   │       │   │   ├── VotingSystem.kt
│   │   │       │   │   ├── ConflictResolver.kt
│   │   │       │   │   └── strategies/
│   │   │       │   │       ├── ConsensusStrategy.kt
│   │   │       │   │       ├── VotingStrategy.kt
│   │   │       │   │       ├── ReasoningQualityStrategy.kt
│   │   │       │   │       ├── MergeStrategy.kt
│   │   │       │   │       └── TokenOptimizationStrategy.kt
│   │   │       │   │
│   │   │       │   ├── context/                  # Shared context management
│   │   │       │   │   ├── ContextModule.kt
│   │   │       │   │   ├── MemoryManager.kt
│   │   │       │   │   ├── StateManager.kt
│   │   │       │   │   ├── FileRegistry.kt
│   │   │       │   │   └── ArtifactStore.kt
│   │   │       │   │
│   │   │       │   └── metrics/                  # Token tracking & analytics
│   │   │       │       ├── MetricsModule.kt
│   │   │       │       ├── TokenTracker.kt
│   │   │       │       ├── PerformanceMonitor.kt
│   │   │       │       ├── DecisionAnalytics.kt
│   │   │       │       └── AlertSystem.kt
│   │   │       │
│   │   │       ├── mcp/
│   │   │       │   ├── McpServerImpl.kt          # MCP server implementation
│   │   │       │   │
│   │   │       │   ├── tools/                    # MCP tools exposed to agents
│   │   │       │   │   ├── CreateConsensusTaskTool.kt
│   │   │       │   │   ├── GetPendingTasksTool.kt
│   │   │       │   │   ├── SubmitInputTool.kt
│   │   │       │   │   ├── GetTaskStatusTool.kt
│   │   │       │   │   ├── ContinueTaskTool.kt
│   │   │       │   │   └── CreateSimpleTaskTool.kt
│   │   │       │   │
│   │   │       │   └── resources/                # MCP resources
│   │   │       │       ├── TasksResource.kt
│   │   │       │       ├── ProposalsResource.kt
│   │   │       │       ├── ContextResource.kt
│   │   │       │       └── MetricsResource.kt
│   │   │       │
│   │   │       ├── storage/
│   │   │       │   ├── Database.kt               # Simple connection manager
│   │   │       │   ├── Transaction.kt            # Transaction helper
│   │   │       │   │
│   │   │       │   ├── repositories/             # Raw JDBC repositories
│   │   │       │   │   ├── TaskRepository.kt
│   │   │       │   │   ├── ProposalRepository.kt
│   │   │       │   │   ├── DecisionRepository.kt
│   │   │       │   │   ├── MetricsRepository.kt
│   │   │       │   │   └── ContextSnapshotRepository.kt
│   │   │       │   │
│   │   │       │   └── schema/
│   │   │       │       └── Schema.kt             # SQL DDL statements
│   │   │       │
│   │   │       ├── domain/                       # Domain models (data classes)
│   │   │       │   ├── Agent.kt
│   │   │       │   ├── Task.kt
│   │   │       │   ├── Proposal.kt
│   │   │       │   ├── Decision.kt
│   │   │       │   ├── Context.kt
│   │   │       │   ├── Metrics.kt
│   │   │       │   └── RoutingDecision.kt
│   │   │       │
│   │   │       ├── agents/                       # Agent implementations
│   │   │       │   ├── ClaudeCodeAgent.kt
│   │   │       │   ├── CodexCLIAgent.kt
│   │   │       │   ├── GeminiAgent.kt            # PLACEHOLDER
│   │   │       │   ├── QwenAgent.kt              # PLACEHOLDER
│   │   │       │   ├── DeepSeekCoderAgent.kt     # PLACEHOLDER
│   │   │       │   │
│   │   │       │   └── factories/                # Agent factories (SPI)
│   │   │       │       ├── ClaudeCodeAgentFactory.kt
│   │   │       │       ├── CodexCLIAgentFactory.kt
│   │   │       │       ├── GeminiAgentFactory.kt
│   │   │       │       ├── QwenAgentFactory.kt
│   │   │       │       └── DeepSeekCoderAgentFactory.kt
│   │   │       │
│   │   │       ├── workflows/                    # Workflow orchestration
│   │   │       │   ├── WorkflowExecutor.kt
│   │   │       │   ├── SoloWorkflow.kt
│   │   │       │   ├── SequentialWorkflow.kt
│   │   │       │   ├── ConsensusWorkflow.kt
│   │   │       │   └── AdaptiveWorkflow.kt
│   │   │       │
│   │   │       ├── config/
│   │   │       │   ├── Configuration.kt
│   │   │       │   └── ConfigLoader.kt
│   │   │       │
│   │   │       └── utils/
│   │   │           ├── Logger.kt
│   │   │           ├── TokenEstimator.kt
│   │   │           └── IdGenerator.kt
│   │   │
│   │   └── resources/
│   │       ├── application.conf                  # HOCON configuration
│   │       ├── logback.xml                       # Logging config
│   │       ├── schema.sql                        # Database schema
│   │       │
│   │       └── META-INF/
│   │           └── services/
│   │               └── com.orchestrator.core.AgentFactory  # SPI registry
│   │
│   └── test/
│       └── kotlin/
│           └── com/orchestrator/
│               ├── modules/
│               │   ├── RoutingModuleTest.kt
│               │   ├── ConsensusModuleTest.kt
│               │   └── MetricsModuleTest.kt
│               │
│               ├── workflows/
│               │   └── WorkflowIntegrationTest.kt
│               │
│               └── integration/
│                   └── McpServerIntegrationTest.kt
│
└── dao.duckdb  # Created at runtime
```

---

## Core Concepts

### 1. Agent Abstraction

All AI agents implement the `Agent` interface:

```kotlin
interface Agent {
    val id: AgentId
    val type: AgentType
    val capabilities: Set<Capability>
    val strengths: List<Strength>
    
    suspend fun isAvailable(): Boolean
    suspend fun getStatus(): AgentStatus
}
```

**Two agent types:**
- **McpAgent**: Passive agents connected via MCP (Claude Code, Codex CLI)
- **ApiAgent**: Active agents callable via direct API (Gemini, Qwen - future)

### 2. Plugin Architecture (Zero Hardcoding)

Agents are discovered via **Java SPI (Service Provider Interface)**:

1. Create `AgentFactory` implementation
2. Register in `META-INF/services/com.orchestrator.core.AgentFactory`
3. Add configuration to `agents.toml`
4. **No code changes needed** - factory registry auto-discovers

This enables:
- ✅ Adding new agents without modifying core code
- ✅ External plugins (drop JAR in `~/.orchestrator/plugins/`)
- ✅ Configuration-driven agent management

### 3. Routing Strategies

**Task Classification** → **Strategy Selection** → **Agent Selection**

**Strategies:**
- **Solo**: Single agent handles task
- **Sequential**: Planner (Codex) → Implementer (Claude)
- **Review**: Implementer → Reviewer (lightweight)
- **Parallel**: Both agents analyze independently
- **Consensus**: Full dual-implementation with voting
- **Adaptive**: Context-based dynamic routing

### 4. Consensus Mechanisms

**Strategies:**
- **Voting**: Simple approval threshold
- **Reasoning Quality**: Score based on depth, edge cases, trade-offs
- **Merge**: Combine best elements from multiple proposals
- **Token Optimization**: Best quality/token ratio

### 5. Conversation-Based Handoff (User Workflow)

**Recommended workflow** for multi-agent coordination:

```
User works primarily with ONE agent (e.g., Claude Code)

Simple tasks → Agent handles alone
Complex tasks → Agent suggests: "Get Codex's input?"
Critical tasks → Agent auto-creates consensus task

User can OVERRIDE agent decisions:
  - Force consensus: "Build auth (get Codex's input)"
  - Prevent consensus: "Fix bug NOW - skip consensus"
  - Assign to agent: "Ask Codex to design the schema"
  - Emergency bypass: "Production down - just implement"

User switches to other agent when notified
Agent calls orchestrator tools:
  - get_pending_tasks()
  - submit_input(taskId, plan)
  - get_task_status(taskId)
  
User returns to primary agent
Agent continues with context from other agent
```

**User Control Levels:**

1. **Implicit (Default)**: Let agent decide based on analysis
2. **Natural Language Directives**: Include hints in request
   - Force consensus: "get Codex's input", "need consensus"
   - Prevent consensus: "solo", "skip review", "emergency"
   - Assign agent: "ask Codex", "have Claude"
3. **Explicit (Future)**: CLI flags or MCP tool parameters

**Key MCP Tools:**
- `create_consensus_task(task, forceConsensus?, userDirective?)` - Create task requiring both agents
- `create_simple_task(task, skipConsensus?, userDirective?)` - Single-agent execution
- `assign_task(task, targetAgent)` - User-directed agent assignment
- `get_pending_tasks(agentId?)` - Show tasks waiting for input
- **`respond_to_task(taskId, response)`** - **RECOMMENDED**: Load context + submit response in one call (combines continue + submit)
- `submit_input(taskId, input)` - Submit analysis/plan/review (for advanced workflows)
- `continue_task(taskId)` - Get full context to continue (for advanced workflows requiring separate analysis)
- `get_task_status(taskId)` - Check if other agent responded

---

## Database Schema (DuckDB)

### Key Tables

```sql
-- Tasks with rich metadata
CREATE TABLE tasks (
    id TEXT PRIMARY KEY,
    description TEXT,
    task_type TEXT,
    complexity INTEGER,
    risk_level INTEGER,
    status TEXT,
    assigned_agents TEXT[], -- DuckDB arrays
    routing_strategy TEXT,
    created_at TIMESTAMP,
    completed_at TIMESTAMP,
    tokens_used INTEGER,
    metadata JSON,  -- DuckDB native JSON
    result TEXT
);

-- Proposals with nested data
CREATE TABLE proposals (
    id TEXT PRIMARY KEY,
    task_id TEXT,
    agent_id TEXT,
    solution JSON,  -- Complex solution object
    reasoning TEXT,
    confidence DOUBLE,
    token_cost INTEGER,
    reviews JSON[], -- Array of review objects
    created_at TIMESTAMP
);

-- Decisions with full context
CREATE TABLE decisions (
    id TEXT PRIMARY KEY,
    task_id TEXT,
    proposals JSON[],
    strategy_used TEXT,
    consensus_reached BOOLEAN,
    final_solution JSON,
    confidence DOUBLE,
    tokens_total INTEGER,
    tokens_saved INTEGER,
    created_at TIMESTAMP
);

-- Time-series metrics
CREATE TABLE metrics_timeseries (
    timestamp TIMESTAMP,
    metric_name TEXT,
    agent_id TEXT,
    value DOUBLE,
    metadata JSON
);
```

**Why DuckDB:**
- Analytical queries (aggregations, time-series, pattern learning)
- Columnar storage (compression, fast analytics)
- Native JSON/array support
- No separate server process
- Single file database

---

## Configuration

### Agent Configuration (`config/agents.toml`)

```toml
[[agents]]
id = "claude-code"
type = "CLAUDE_CODE"
name = "Claude Code"
enabled = true

capabilities = [
    "CODE_GENERATION",
    "CODE_REVIEW",
    "FILE_OPERATIONS"
]

[agents.strengths]
CODE_QUALITY = 0.95
IMPLEMENTATION = 0.9

[agents.connection]
type = "MCP_HTTP"
url = "http://localhost:3000/mcp"

[agents.preferences]
token_budget_default = 50000
preferred_for = ["implementation", "refactoring"]

# Future agents - placeholders
[[agents]]
id = "gemini"
type = "GEMINI"
enabled = false  # Not yet implemented

[agents.connection]
type = "API_DIRECT"
api_key = "${GEMINI_API_KEY}"  # Environment variable
```

### Server Configuration (`config/application.conf`)

```hocon
server {
    transport = "http"
    host = "localhost"
    port = 3000
}

storage {
    type = "duckdb"
    path = "./dao.duckdb"
}

routing {
    default_strategy = "adaptive"
    simple_task_threshold = 3
    critical_risk_threshold = 7
}

consensus {
    default_strategy = "adaptive"
    approval_threshold = 0.75
}
```

---

## Development Guidelines

### Adding a New Agent Type

**Example: Adding Gemini support**

1. **Implement Agent class:**
```kotlin
// agents/GeminiAgent.kt
class GeminiAgent(
    config: AgentConfig,
    private val apiKey: String
) : ApiAgent(config) {
    override suspend fun execute(prompt: String, context: Map<String, Any>): String {
        // Call Gemini API
        return geminiApi.generate(prompt, context)
    }
}
```

2. **Create Factory:**
```kotlin
// agents/factories/GeminiAgentFactory.kt
class GeminiAgentFactory : AgentFactory {
    override val supportedType = "GEMINI"
    
    override fun createAgent(config: AgentConfig): Agent {
        val apiKey = config.connectionConfig["api_key"] ?: error("Missing API key")
        return GeminiAgent(config, apiKey)
    }
}
```

3. **Register via SPI:**
```
# META-INF/services/com.orchestrator.core.AgentFactory
com.orchestrator.agents.factories.GeminiAgentFactory
```

4. **Enable in config:**
```toml
[[agents]]
id = "gemini"
type = "GEMINI"
enabled = true
```

**No core code changes needed!**

### Database Access Pattern

```kotlin
// Raw JDBC with helper extensions
fun TaskRepository.insert(task: Task) {
    val sql = """
        INSERT INTO tasks 
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """
    
    connection.prepareStatement(sql).use { stmt ->
        stmt.bindTask(task)  // Extension function
        stmt.executeUpdate()
    }
}

// ResultSet mapping
fun ResultSet.toTask(): Task {
    return Task(
        id = getString("id"),
        description = getString("description"),
        taskType = TaskType.valueOf(getString("task_type")),
        // ... all fields
    )
}
```

**Never use ORM - we want full SQL control**

### Testing Strategy

- **Unit tests**: Individual modules (routing, consensus, metrics)
- **Integration tests**: Full workflow end-to-end
- **Database tests**: SQL queries, migrations
- **MCP tests**: Tool calls, resource access

---

## Future Enhancements

### Planned Features
- [ ] Agent performance learning (ML-based routing)
- [ ] Cost optimization across different model pricing
- [ ] Multi-step workflow templates
- [ ] Human-in-the-loop approval for critical decisions
- [ ] Dashboard/UI for monitoring
- [ ] Distributed consensus (multiple users)
- [ ] Agent capability auto-discovery
- [ ] Plugin marketplace

### Placeholder Agent Integrations
- [ ] Google Gemini (multimodal, long context)
- [ ] Alibaba Qwen (cost-effective)
- [ ] DeepSeek Coder (code quality specialist)
- [ ] OpenAI O1/O3 (advanced reasoning)
- [ ] Meta Llama (local deployment)
- [ ] Mistral (open source option)

---

## Common Patterns

### Creating a Consensus Task

```kotlin
// User in Claude Code
You: "Build OAuth2 authentication (critical)"

// Claude Code internally calls:
orchestrator.create_consensus_task(
    task = Task(
        description = "Build OAuth2 authentication",
        complexity = 8,
        riskLevel = 9
    )
)

// Returns: Task #123 created, waiting for Codex input
```

### User Control Patterns

```kotlin
// Pattern 1: Force consensus (agent would skip it)
You: "Refactor validation logic (get Codex to review)"

// Agent detects directive: "get Codex to review"
orchestrator.create_consensus_task(
    task = Task(description = "..."),
    forceConsensus = true,
    userDirective = "get Codex to review"
)

// Pattern 2: Prevent consensus (emergency)
You: "Fix auth bug NOW - production down, skip consensus"

// Agent detects: "skip consensus" + "production down"
orchestrator.create_simple_task(
    task = Task(description = "..."),
    skipConsensus = true,
    userDirective = "emergency bypass"
)

// Pattern 3: Assign to specific agent
You: "Ask Codex to design the database schema"

// Agent detects: "Ask Codex"
orchestrator.assign_task(
    task = Task(description = "Design database schema"),
    targetAgent = "codex-cli"
)
```

### Agent Switching Workflow

#### Simple Workflow (RECOMMENDED - using respond_to_task)

```kotlin
// User switches to Codex CLI
You: "Check pending tasks"

// Codex internally calls:
orchestrator.get_pending_tasks()

// Returns: [Task #123: OAuth2 system]

You: "Respond to task 123"
// Codex uses the unified tool (loads context + submits in one call):
orchestrator.respond_to_task(
    taskId = 123,
    response = {
        content = architecturalPlan,
        inputType = "ARCHITECTURAL_PLAN",
        confidence = 0.85
    }
)

// User switches back to Claude Code
You: "Continue task 123"

// Claude gets context and implements:
orchestrator.continue_task(123)
```

#### Advanced Workflow (using continue_task + submit_input separately)

```kotlin
// When you need to analyze before deciding to submit
You: "Check task 123"

// Step 1: Load and analyze
orchestrator.continue_task(123)
// Agent analyzes proposals, identifies issues

// Step 2: Decide whether to submit
if (needsRevision) {
    orchestrator.submit_input(
        taskId = 123,
        input = revisedPlan
    )
}
```

---

## Critical Architecture Decisions

### Why HTTP MCP Transport?
- Both Claude Code and Codex CLI support HTTP
- Shared server instance (required for coordination)
- Easier debugging than stdio
- Future: dashboard can connect too

### Why Raw JDBC (not ORM)?
- Full control over SQL
- Transparent (see exact queries)
- DuckDB-specific features (JSON, arrays)
- No hidden N+1 queries
- Minimal dependencies

### Why No Connection Pooling?
- Single-user desktop application
- Low concurrency
- DuckDB is embedded (no network)
- Single connection is thread-safe

### Why Plugin Architecture?
- Zero hardcoding of agent types
- External plugins possible
- Configuration-driven
- Extensible without core changes

---

## Getting Started (for Claude Code)

When implementing this project:

1. **Start with domain models** (`domain/`) - these are the foundation
2. **Implement database layer** (`storage/`) - raw JDBC, manual mapping
3. **Build agent abstraction** (`core/AgentFactory`, `AgentRegistry`)
4. **Implement routing module** (`modules/routing/`)
5. **Add consensus module** (`modules/consensus/`)
6. **Create MCP server** (`mcp/McpServerImpl.kt`)
7. **Implement MCP tools** (`mcp/tools/`)
8. **Add workflows** (`workflows/`)
9. **Configuration loader** (`config/ConfigLoader.kt`)
10. **Main entry point** (`Main.kt`)

**Test continuously** - write tests as you build each module.

---

## Important Notes for Implementation

### DuckDB-Specific SQL
- Use JSON: `solution->>'confidence'`
- Use arrays: `assigned_agents TEXT[]`
- Time functions: `DATE_TRUNC('day', timestamp)`
- Analytical: `PERCENTILE_CONT(0.95) WITHIN GROUP`

### Coroutines Best Practices
- Use `suspend` for I/O operations
- `Flow` for reactive streams
- `Channel` for event bus
- `Mutex` for write coordination (if needed)

### MCP Tool Design
- Tools should be **idempotent** where possible
- Always validate input
- Return structured data (JSON)
- Include error details in responses
- **Support user directives** in tool parameters:
  - `forceConsensus` - user explicitly requested consensus
  - `skipConsensus` - user bypassed consensus (emergency)
  - `userDirective` - original user text for context
  - `targetAgent` - user-specified agent assignment

### User Directive Parsing
```kotlin
// Agents should parse user intent from natural language
data class UserDirective(
    val forceConsensus: Boolean,
    val preventConsensus: Boolean,
    val assignToAgent: String?,
    val isEmergency: Boolean
)

// Detection patterns
val forceConsensus = request.containsAny(
    "get.*input", "need consensus", "want.*review"
)
val preventConsensus = request.containsAny(
    "solo", "skip consensus", "emergency", "production down"
)
val assignToAgent = detectAgentName(request)  // "ask Codex", etc.
```

### Agent Request Handling Flow
1. **Parse user directives** - check for explicit routing hints
2. **Respect user overrides** - forceConsensus/preventConsensus
3. **Auto-analyze if no directive** - complexity/risk assessment
4. **Ask for confirmation** - when auto-detecting high-risk
5. **Log decisions** - track whether user forced/bypassed

### Configuration
- Use environment variables for secrets: `${API_KEY}`
- Support defaults: `${VAR:default_value}`
- Validate on startup
- Hot-reload not required (restart server)

---

## Questions to Consider During Implementation

1. **Error Handling**: How to handle agent failures gracefully?
2. **Timeout Management**: What if an agent never responds?
3. **State Recovery**: How to resume interrupted workflows?
4. **Metrics Persistence**: When to flush metrics buffer?
5. **Task Expiration**: Should old pending tasks auto-expire?
6. **Agent Health Checks**: How to detect offline agents?
7. **Concurrent Tasks**: Can one agent handle multiple tasks?
8. **Priority Queuing**: Should critical tasks jump the queue?
9. **User Directive Conflicts**: What if user says "get Codex's input" but Codex is offline?
10. **Directive Detection Accuracy**: How to handle ambiguous user statements?
11. **Emergency Override Logging**: How to audit bypassed consensus decisions?
12. **User Confirmation UX**: Best way to ask for consensus confirmation?

---

## Dependencies Reference

```kotlin
// build.gradle.kts key dependencies
dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    
    // MCP
    implementation("io.github.modelcontextprotocol:kotlin-sdk:VERSION")
    
    // HTTP Server
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-netty:2.3.7")
    
    // Database
    implementation("org.duckdb:duckdb_jdbc:0.10.0")
    
    // Config
    implementation("com.typesafe:config:1.4.3")
    
    // Testing
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.mockk:mockk:1.13.9")
}
```

---

## Glossary

- **Agent**: AI model/assistant (Claude Code, Codex CLI, Gemini, etc.)
- **MCP**: Model Context Protocol (standard for AI-tool communication)
- **Orchestrator**: This system (coordinates multiple agents)
- **Task**: Work unit requiring agent(s) input
- **Proposal**: Agent's suggested solution to a task
- **Consensus**: Agreement between multiple agents
- **Routing**: Deciding which agent(s) should handle a task
- **Handoff**: Transferring task context between agents
- **Capability**: What an agent can do (code generation, review, etc.)
- **Strength**: How well an agent does something (0.0-1.0 score)
- **User Directive**: Natural language hint from user about routing preference
- **Force Consensus**: User explicitly requests multi-agent collaboration
- **Prevent Consensus**: User bypasses consensus (e.g., emergency, preference)
- **Emergency Bypass**: User skips normal workflow due to production issue
- **Agent Assignment**: User specifies which agent should handle task

---

**This document is a living guide. Update it as the architecture evolves.**
