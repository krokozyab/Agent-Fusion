# Dual-Agent Orchestrator - Implementation Plan

## Document Control
- **Project**: Dual-Agent Orchestrator MCP Server
- **Version**: 1.0
- **Date**: October 5, 2025
- **Purpose**: Step-by-step implementation tasks for Claude Code and Codex CLI

---

## How to Use This Plan

### Task Format
```
TASK-XXX: Brief Description
├── Priority: [P0-Critical | P1-High | P2-Medium | P3-Low]
├── Estimated Time: [30min | 1h | 2h | 4h]
├── Dependencies: [TASK-YYY, TASK-ZZZ]
├── Assigned To: [Claude Code | Codex CLI | Either]
└── Phase: [1-Foundation | 2-Core | 3-Integration | 4-Enhancement]
```

### Execution Order
1. Complete all **P0** tasks first
2. Follow dependency order (check Dependencies field)
3. Run tests after each task
4. Commit after each completed task

### Agent Strengths
- **Claude Code**: Implementation, file operations, testing, refactoring
- **Codex CLI**: Architecture, design patterns, complex algorithms, planning

---

## Phase 1: Foundation (Setup & Core Models)

### TASK-001: Project Setup
**Priority**: P0  
**Time**: 30min  
**Dependencies**: None  
**Assigned To**: Either  
**Phase**: 1-Foundation

**Description**: Initialize Kotlin project with Gradle

**Tasks**:
1. Create `build.gradle.kts` with Kotlin DSL
2. Add dependencies:
   - Kotlin coroutines
   - kotlinx-serialization
   - Ktor server
   - DuckDB JDBC driver
3. Create `settings.gradle.kts`
4. Create basic directory structure
5. Add `.gitignore` for Kotlin/Gradle

**Acceptance Criteria**:
- `./gradlew build` succeeds
- Dependencies resolve correctly
- Project structure matches CLAUDE.md

**Files to Create**:
- `build.gradle.kts`
- `settings.gradle.kts`
- `.gitignore`
- `README.md`

---

### TASK-002: Domain Models - Agent
**Priority**: P0  
**Time**: 1h  
**Dependencies**: TASK-001  
**Assigned To**: Either  
**Phase**: 1-Foundation

**Description**: Create Agent domain model and related types

**Tasks**:
1. Create `domain/Agent.kt`:
   - `AgentId` data class
   - `AgentType` enum (CLAUDE_CODE, CODEX_CLI, GEMINI, etc.)
   - `AgentStatus` enum (ONLINE, OFFLINE, BUSY)
   - `Agent` interface
2. Create `domain/Capability.kt`:
   - `Capability` enum (CODE_GENERATION, CODE_REVIEW, etc.)
   - `Strength` data class (capability, score)
3. Create `domain/AgentConfig.kt`:
   - Configuration data class

**Acceptance Criteria**:
- All classes compile
- Enums have proper values
- Data classes have proper equals/hashCode

**Files to Create**:
- `src/main/kotlin/com/orchestrator/domain/Agent.kt`
- `src/main/kotlin/com/orchestrator/domain/Capability.kt`
- `src/main/kotlin/com/orchestrator/domain/AgentConfig.kt`

---

### TASK-003: Domain Models - Task
**Priority**: P0  
**Time**: 1h  
**Dependencies**: TASK-001  
**Assigned To**: Either  
**Phase**: 1-Foundation

**Description**: Create Task domain model and related types

**Tasks**:
1. Create `domain/Task.kt`:
   - `TaskId` type alias or data class
   - `TaskType` enum (IMPLEMENTATION, ARCHITECTURE, REVIEW, etc.)
   - `TaskStatus` enum (PENDING, IN_PROGRESS, WAITING_INPUT, COMPLETED, FAILED)
   - `RoutingStrategy` enum (SOLO, CONSENSUS, SEQUENTIAL, etc.)
   - `Task` data class with all fields
2. Add complexity/risk validation (1-10 range)

**Acceptance Criteria**:
- Task data class immutable
- All enums defined
- Validation logic present
- Timestamps use proper types

**Files to Create**:
- `src/main/kotlin/com/orchestrator/domain/Task.kt`

---

### TASK-004: Domain Models - Proposal
**Priority**: P0  
**Time**: 45min  
**Dependencies**: TASK-003  
**Assigned To**: Either  
**Phase**: 1-Foundation

**Description**: Create Proposal domain model

**Tasks**:
1. Create `domain/Proposal.kt`:
   - `ProposalId` type
   - `Proposal` data class
   - `InputType` enum (ARCHITECTURAL_PLAN, CODE_REVIEW, etc.)
   - Confidence score field (0.0-1.0)
   - Token cost tracking

**Acceptance Criteria**:
- Proposal links to Task and Agent
- Content stored as flexible JSON-compatible structure
- Confidence validation

**Files to Create**:
- `src/main/kotlin/com/orchestrator/domain/Proposal.kt`

---

### TASK-005: Domain Models - Decision
**Priority**: P0  
**Time**: 45min  
**Dependencies**: TASK-004  
**Assigned To**: Either  
**Phase**: 1-Foundation

**Description**: Create Decision domain model

**Tasks**:
1. Create `domain/Decision.kt`:
   - `DecisionId` type
   - `Decision` data class
   - Link to task and proposals
   - Consensus result fields
   - Token savings calculation

**Acceptance Criteria**:
- Decision records full context
- Token savings calculated correctly
- Immutable data structure

**Files to Create**:
- `src/main/kotlin/com/orchestrator/domain/Decision.kt`

---

### TASK-006: Domain Models - User Directive
**Priority**: P1  
**Time**: 30min  
**Dependencies**: TASK-001  
**Assigned To**: Either  
**Phase**: 1-Foundation

**Description**: Create UserDirective model for parsing user intent

**Tasks**:
1. Create `domain/UserDirective.kt`:
   - `UserDirective` data class
   - Fields: forceConsensus, preventConsensus, assignToAgent, isEmergency
   - originalText field for context

**Acceptance Criteria**:
- All boolean flags default to false
- Optional assignToAgent field
- Preserves original user text

**Files to Create**:
- `src/main/kotlin/com/orchestrator/domain/UserDirective.kt`

---

### TASK-007: Database Schema Definition
**Priority**: P0  
**Time**: 1h  
**Dependencies**: TASK-003, TASK-004, TASK-005  
**Assigned To**: Codex CLI  
**Phase**: 1-Foundation

**Description**: Define DuckDB schema SQL

**Tasks**:
1. Create `storage/schema/Schema.kt`:
   - SQL DDL for tasks table
   - SQL DDL for proposals table
   - SQL DDL for decisions table
   - SQL DDL for metrics_timeseries table
   - SQL DDL for context_snapshots table
2. Add indexes for common queries
3. Add comments documenting each field

**Acceptance Criteria**:
- Valid DuckDB SQL syntax
- All foreign key relationships defined
- Indexes on frequently queried columns
- Uses DuckDB-specific types (JSON, ARRAY)

**Files to Create**:
- `src/main/kotlin/com/orchestrator/storage/schema/Schema.kt`

---

### TASK-008: Database Connection Manager
**Priority**: P0  
**Time**: 1h  
**Dependencies**: TASK-007  
**Assigned To**: Claude Code  
**Phase**: 1-Foundation

**Description**: Create simple database connection manager

**Tasks**:
1. Create `storage/Database.kt`:
   - Initialize DuckDB connection
   - Schema initialization on first run
   - Connection lifecycle management
   - Configuration from HOCON
2. Add connection health check
3. Implement graceful shutdown

**Acceptance Criteria**:
- Single connection pattern
- Auto-creates database file if missing
- Executes schema SQL on initialization
- Proper resource cleanup

**Files to Create**:
- `src/main/kotlin/com/orchestrator/storage/Database.kt`

---

### TASK-009: Transaction Helper
**Priority**: P0  
**Time**: 45min  
**Dependencies**: TASK-008  
**Assigned To**: Claude Code  
**Phase**: 1-Foundation

**Description**: Create transaction management utilities

**Tasks**:
1. Create `storage/Transaction.kt`:
   - `transaction` suspend function with lambda
   - Automatic commit on success
   - Automatic rollback on error
   - Nested transaction detection

**Acceptance Criteria**:
- Works with coroutines
- Handles exceptions properly
- Logs transaction lifecycle
- No resource leaks

**Files to Create**:
- `src/main/kotlin/com/orchestrator/storage/Transaction.kt`

---

### TASK-010: Configuration Models
**Priority**: P0  
**Time**: 1h  
**Dependencies**: TASK-001  
**Assigned To**: Either  
**Phase**: 1-Foundation

**Description**: Create configuration data classes

**Tasks**:
1. Create `config/Configuration.kt`:
   - `ServerConfig` (host, port, transport)
   - `StorageConfig` (database path)
   - `RoutingConfig` (thresholds, default strategy)
   - `ConsensusConfig` (strategies, thresholds)
2. Add validation logic

**Acceptance Criteria**:
- All configs have sensible defaults
- Validation prevents invalid values
- Support for environment variables

**Files to Create**:
- `src/main/kotlin/com/orchestrator/config/Configuration.kt`

---

### TASK-011: TOML Configuration Parser
**Priority**: P1  
**Time**: 1h  
**Dependencies**: TASK-010  
**Assigned To**: Claude Code  
**Phase**: 1-Foundation

**Description**: Parse agents.toml configuration file

**Tasks**:
1. Create `config/ConfigLoader.kt`:
   - Parse `agents.toml` with TOML library
   - Load agent configurations
   - Support environment variable substitution
   - Validate on load
2. Add error reporting for invalid configs

**Acceptance Criteria**:
- Parses valid TOML correctly
- Environment variables resolved
- Clear error messages for issues
- Example `agents.toml` provided

**Files to Create**:
- `src/main/kotlin/com/orchestrator/config/ConfigLoader.kt`
- `config/agents.toml.example`

---

## Phase 2: Core Components (Repositories & Modules)

### TASK-012: Task Repository
**Priority**: P0  
**Time**: 2h  
**Dependencies**: TASK-008, TASK-009  
**Assigned To**: Claude Code  
**Phase**: 2-Core

**Description**: Implement TaskRepository with raw JDBC

**Tasks**:
1. Create `storage/repositories/TaskRepository.kt`:
   - `insert(task: Task)`
   - `findById(id: TaskId): Task?`
   - `findByStatus(status: TaskStatus): List<Task>`
   - `findByAgent(agentId: AgentId): List<Task>`
   - `update(task: Task)`
   - `delete(id: TaskId)`
2. Add helper methods for ResultSet mapping
3. Use PreparedStatements for all queries

**Acceptance Criteria**:
- All CRUD operations work
- No SQL injection vulnerabilities
- Proper error handling
- Tests pass

**Files to Create**:
- `src/main/kotlin/com/orchestrator/storage/repositories/TaskRepository.kt`
- `src/test/kotlin/com/orchestrator/storage/repositories/TaskRepositoryTest.kt`

---

### TASK-013: Proposal Repository
**Priority**: P0  
**Time**: 1h  
**Dependencies**: TASK-012  
**Assigned To**: Claude Code  
**Phase**: 2-Core

**Description**: Implement ProposalRepository

**Tasks**:
1. Create `storage/repositories/ProposalRepository.kt`:
   - CRUD operations for proposals
   - `findByTask(taskId: TaskId): List<Proposal>`
   - `findByAgent(agentId: AgentId): List<Proposal>`
   - JSON content serialization

**Acceptance Criteria**:
- Proposals stored with JSON content
- Foreign key constraints respected
- Queries efficient

**Files to Create**:
- `src/main/kotlin/com/orchestrator/storage/repositories/ProposalRepository.kt`
- `src/test/kotlin/com/orchestrator/storage/repositories/ProposalRepositoryTest.kt`

---

### TASK-014: Decision Repository
**Priority**: P1  
**Time**: 1h  
**Dependencies**: TASK-013  
**Assigned To**: Claude Code  
**Phase**: 2-Core

**Description**: Implement DecisionRepository

**Tasks**:
1. Create `storage/repositories/DecisionRepository.kt`:
   - CRUD operations for decisions
   - `findByTask(taskId: TaskId): Decision?`
   - Store complete decision context

**Acceptance Criteria**:
- Links to proposals correctly
- Token calculations stored
- Audit trail complete

**Files to Create**:
- `src/main/kotlin/com/orchestrator/storage/repositories/DecisionRepository.kt`

---

### TASK-015: Metrics Repository
**Priority**: P2  
**Time**: 1h  
**Dependencies**: TASK-012  
**Assigned To**: Claude Code  
**Phase**: 2-Core

**Description**: Implement MetricsRepository for time-series data

**Tasks**:
1. Create `storage/repositories/MetricsRepository.kt`:
   - `recordMetric(name, value, metadata)`
   - `queryMetrics(name, timeRange): List<Metric>`
   - `aggregateMetrics(name, timeRange, aggregation)`
   - Time-series specific queries

**Acceptance Criteria**:
- Efficient time-range queries
- Aggregations work correctly
- Handles high-volume inserts

**Files to Create**:
- `src/main/kotlin/com/orchestrator/storage/repositories/MetricsRepository.kt`

---

### TASK-016: Token Estimator Utility
**Priority**: P1  
**Time**: 1h  
**Dependencies**: TASK-001  
**Assigned To**: Codex CLI  
**Phase**: 2-Core

**Description**: Create token estimation utility

**Tasks**:
1. Create `utils/TokenEstimator.kt`:
   - Estimate tokens from text length
   - Use simple heuristic (chars / 4)
   - Support different model token ratios
   - Add Claude/Codex specific adjustments

**Acceptance Criteria**:
- Estimates within 10% accuracy
- Fast computation
- No external API calls

**Files to Create**:
- `src/main/kotlin/com/orchestrator/utils/TokenEstimator.kt`
- `src/test/kotlin/com/orchestrator/utils/TokenEstimatorTest.kt`

---

### TASK-017: User Directive Parser
**Priority**: P1  
**Time**: 2h  
**Dependencies**: TASK-006  
**Assigned To**: Codex CLI  
**Phase**: 2-Core

**Description**: Implement user directive parsing logic

**Tasks**:
1. Create `modules/routing/DirectiveParser.kt`:
   - `parseUserDirective(request: String): UserDirective`
   - Regex patterns for force consensus keywords
   - Regex patterns for prevent consensus keywords
   - Agent name detection
   - Emergency keyword detection
2. Add keyword priority handling
3. Handle conflicting directives

**Acceptance Criteria**:
- Detects all directive keywords from spec
- Handles ambiguous cases gracefully
- Returns parsed directive with confidence
- Well tested with edge cases

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/routing/DirectiveParser.kt`
- `src/test/kotlin/com/orchestrator/modules/routing/DirectiveParserTest.kt`

---

### TASK-018: Task Classifier
**Priority**: P0  
**Time**: 2h  
**Dependencies**: TASK-003, TASK-016  
**Assigned To**: Codex CLI  
**Phase**: 2-Core

**Description**: Implement task complexity/risk classification

**Tasks**:
1. Create `modules/routing/TaskClassifier.kt`:
   - `estimateComplexity(description: String): Int`
   - `estimateRisk(description: String): Int`
   - Keyword-based heuristics
   - Length-based scoring
   - Critical keyword detection (security, auth, payment)
2. Add confidence scoring

**Acceptance Criteria**:
- Complexity scored 1-10
- Risk scored 1-10
- Critical keywords identified
- Fast execution (<10ms)

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/routing/TaskClassifier.kt`
- `src/test/kotlin/com/orchestrator/modules/routing/TaskClassifierTest.kt`

---

### TASK-019: Agent Registry
**Priority**: P0  
**Time**: 2h  
**Dependencies**: TASK-002, TASK-011  
**Assigned To**: Claude Code  
**Phase**: 2-Core

**Description**: Implement agent registry for managing active agents

**Tasks**:
1. Create `core/AgentRegistry.kt`:
   - Register agents from config
   - `getAgent(id: AgentId): Agent?`
   - `getAgentsByCapability(capability: Capability): List<Agent>`
   - `getAllAgents(): List<Agent>`
   - Update agent status
   - Health check integration
2. Thread-safe operations

**Acceptance Criteria**:
- Loads agents from config
- Query methods efficient
- Status updates atomic
- Concurrent access safe

**Files to Create**:
- `src/main/kotlin/com/orchestrator/core/AgentRegistry.kt`
- `src/test/kotlin/com/orchestrator/core/AgentRegistryTest.kt`

---

### TASK-020: Agent Factory Interface
**Priority**: P0  
**Time**: 1h  
**Dependencies**: TASK-002  
**Assigned To**: Codex CLI  
**Phase**: 2-Core

**Description**: Define Agent Factory SPI interface

**Tasks**:
1. Create `core/AgentFactory.kt`:
   - Interface with `createAgent(config: AgentConfig): Agent`
   - `supportedType: String` property
   - Documentation for implementers
2. Create `core/AgentFactoryRegistry.kt`:
   - ServiceLoader-based discovery
   - Factory registration
   - Factory lookup by type

**Acceptance Criteria**:
- Clean SPI design
- Supports ServiceLoader
- Easy to implement
- Well documented

**Files to Create**:
- `src/main/kotlin/com/orchestrator/core/AgentFactory.kt`
- `src/main/kotlin/com/orchestrator/core/AgentFactoryRegistry.kt`

---

### TASK-021: Agent Selector
**Priority**: P0  
**Time**: 2h  
**Dependencies**: TASK-019  
**Assigned To**: Codex CLI  
**Phase**: 2-Core

**Description**: Implement agent selection algorithm

**Tasks**:
1. Create `modules/routing/AgentSelector.kt`:
   - `selectAgentForTask(task: Task): Agent?`
   - `selectAgentsForConsensus(task: Task): List<Agent>`
   - Capability matching
   - Strength-based scoring
   - Availability checking
   - Load balancing logic
2. Support user-specified agent (userDirective.assignToAgent)

**Acceptance Criteria**:
- Selects best-fit agent
- Respects user directives
- Handles no-available-agent case
- Selection reasoning logged

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/routing/AgentSelector.kt`
- `src/test/kotlin/com/orchestrator/modules/routing/AgentSelectorTest.kt`

---

### TASK-022: Strategy Picker
**Priority**: P0  
**Time**: 2h  
**Dependencies**: TASK-018, TASK-017  
**Assigned To**: Codex CLI  
**Phase**: 2-Core

**Description**: Implement routing strategy selection logic

**Tasks**:
1. Create `modules/routing/StrategyPicker.kt`:
   - `pickStrategy(task: Task, directive: UserDirective): RoutingStrategy`
   - Decision tree implementation
   - User directive override logic
   - Default strategy selection
   - Confidence scoring
2. Log strategy selection reasoning

**Acceptance Criteria**:
- Follows decision tree from spec
- User directives take precedence
- Returns appropriate strategy
- Reasoning captured

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/routing/StrategyPicker.kt`
- `src/test/kotlin/com/orchestrator/modules/routing/StrategyPickerTest.kt`

---

### TASK-023: Routing Module Integration
**Priority**: P0  
**Time**: 1h  
**Dependencies**: TASK-018, TASK-021, TASK-022  
**Assigned To**: Claude Code  
**Phase**: 2-Core

**Description**: Integrate routing components into RoutingModule

**Tasks**:
1. Create `modules/routing/RoutingModule.kt`:
   - Compose classifier, selector, strategy picker
   - `routeTask(task: Task): RoutingDecision`
   - `routeTaskWithDirective(task: Task, directive: UserDirective): RoutingDecision`
   - Create `RoutingDecision` data class
2. Add comprehensive logging

**Acceptance Criteria**:
- All routing components integrated
- Returns complete routing decision
- Handles all strategies
- Well tested

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/routing/RoutingModule.kt`
- `src/main/kotlin/com/orchestrator/domain/RoutingDecision.kt`
- `src/test/kotlin/com/orchestrator/modules/routing/RoutingModuleTest.kt`

---

## Phase 3: Consensus & Context (Multi-Agent Coordination)

### TASK-024: Consensus Strategy Interface
**Priority**: P1  
**Time**: 1h  
**Dependencies**: TASK-004  
**Assigned To**: Codex CLI  
**Phase**: 3-Integration

**Description**: Define consensus strategy interface

**Tasks**:
1. Create `modules/consensus/strategies/ConsensusStrategy.kt`:
   - Interface with `evaluate(proposals: List<Proposal>): ConsensusResult`
   - `ConsensusResult` data class
   - Strategy type enum
2. Document strategy contract

**Acceptance Criteria**:
- Clean interface design
- Extensible for new strategies
- Result includes reasoning

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/consensus/strategies/ConsensusStrategy.kt`

---

### TASK-025: Voting Strategy Implementation
**Priority**: P1  
**Time**: 1h  
**Dependencies**: TASK-024  
**Assigned To**: Claude Code  
**Phase**: 3-Integration

**Description**: Implement voting consensus strategy

**Tasks**:
1. Create `modules/consensus/strategies/VotingStrategy.kt`:
   - Threshold-based approval (default 75%)
   - Count approvals from proposals
   - Return consensus if threshold met
   - Handle ties

**Acceptance Criteria**:
- Voting logic correct
- Configurable threshold
- Clear reasoning in result
- Tests cover edge cases

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/consensus/strategies/VotingStrategy.kt`
- `src/test/kotlin/com/orchestrator/modules/consensus/strategies/VotingStrategyTest.kt`

---

### TASK-026: Reasoning Quality Strategy
**Priority**: P2  
**Time**: 2h  
**Dependencies**: TASK-024  
**Assigned To**: Codex CLI  
**Phase**: 3-Integration

**Description**: Implement reasoning quality scoring strategy

**Tasks**:
1. Create `modules/consensus/strategies/ReasoningQualityStrategy.kt`:
   - Score proposals on reasoning depth
   - Check for edge case consideration
   - Check for trade-off analysis
   - Weight by confidence scores
   - Select highest quality

**Acceptance Criteria**:
- Quality scoring algorithm implemented
- Objective criteria used
- Best reasoning identified
- Scoring transparent

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/consensus/strategies/ReasoningQualityStrategy.kt`

---

### TASK-027: Token Optimization Strategy
**Priority**: P2  
**Time**: 1h  
**Dependencies**: TASK-024, TASK-016  
**Assigned To**: Claude Code  
**Phase**: 3-Integration

**Description**: Implement token-optimized strategy

**Tasks**:
1. Create `modules/consensus/strategies/TokenOptimizationStrategy.kt`:
   - Calculate quality/token ratio
   - Select best value proposal
   - Factor in confidence
   - Calculate savings

**Acceptance Criteria**:
- Ratio calculation correct
- Selects efficient proposal
- Token savings reported

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/consensus/strategies/TokenOptimizationStrategy.kt`

---

### TASK-028: Proposal Manager
**Priority**: P1  
**Time**: 1h  
**Dependencies**: TASK-013  
**Assigned To**: Claude Code  
**Phase**: 3-Integration

**Description**: Manage proposal lifecycle

**Tasks**:
1. Create `modules/consensus/ProposalManager.kt`:
   - `submitProposal(taskId, agentId, content)`
   - `getProposals(taskId): List<Proposal>`
   - `waitForProposals(taskId, timeout): List<Proposal>`
   - Validation logic
2. Support async proposal collection

**Acceptance Criteria**:
- Proposals stored correctly
- Timeout handling works
- Can wait for multiple proposals
- Thread-safe

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/consensus/ProposalManager.kt`

---

### TASK-029: Conflict Resolver
**Priority**: P2  
**Time**: 2h  
**Dependencies**: TASK-028  
**Assigned To**: Codex CLI  
**Phase**: 3-Integration

**Description**: Handle consensus conflicts

**Tasks**:
1. Create `modules/consensus/ConflictResolver.kt`:
   - Detect no-consensus situations
   - Request proposal refinements
   - Escalation to human logic
   - Tiebreaker mechanisms
   - Log resolution path

**Acceptance Criteria**:
- Conflicts detected accurately
- Multiple resolution paths
- Escalation works
- Full audit trail

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/consensus/ConflictResolver.kt`

---

### TASK-030: Consensus Module Integration
**Priority**: P1  
**Time**: 1h  
**Dependencies**: TASK-025, TASK-028  
**Assigned To**: Claude Code  
**Phase**: 3-Integration

**Description**: Integrate consensus components

**Tasks**:
1. Create `modules/consensus/ConsensusModule.kt`:
   - Coordinate proposal collection
   - Select and execute strategy
   - Record decision
   - Return result
   - Support strategy chaining

**Acceptance Criteria**:
- All strategies available
- Strategy selection works
- Decisions recorded
- Error handling robust

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/consensus/ConsensusModule.kt`
- `src/test/kotlin/com/orchestrator/modules/consensus/ConsensusModuleTest.kt`

---

### TASK-031: Context Snapshot Manager
**Priority**: P1  
**Time**: 1h  
**Dependencies**: TASK-012  
**Assigned To**: Claude Code  
**Phase**: 3-Integration

**Description**: Implement state snapshot functionality

**Tasks**:
1. Create `modules/context/StateManager.kt`:
   - `createSnapshot(taskId): SnapshotId`
   - `restoreSnapshot(snapshotId): TaskState`
   - JSON serialization
   - Compression support
2. Add snapshot repository if needed

**Acceptance Criteria**:
- Snapshots capture full state
- Restoration works correctly
- Compressed storage
- Fast operations

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/context/StateManager.kt`

---

### TASK-032: Memory Manager
**Priority**: P2  
**Time**: 1h  
**Dependencies**: TASK-012  
**Assigned To**: Claude Code  
**Phase**: 3-Integration

**Description**: Manage conversation history and context

**Tasks**:
1. Create `modules/context/MemoryManager.kt`:
   - Store conversation messages
   - Retrieve conversation history
   - Context summarization (if exceeds limit)
   - Context pruning

**Acceptance Criteria**:
- History stored efficiently
- Retrieval by task ID
- Summarization when needed
- Memory limits respected

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/context/MemoryManager.kt`

---

### TASK-033: File Registry
**Priority**: P2  
**Time**: 1h  
**Dependencies**: TASK-012  
**Assigned To**: Claude Code  
**Phase**: 3-Integration

**Description**: Track file operations across agents

**Tasks**:
1. Create `modules/context/FileRegistry.kt`:
   - `recordFileOperation(taskId, agentId, operation)`
   - `getFileHistory(taskId): List<FileOperation>`
   - Detect file conflicts
   - Support diffs

**Acceptance Criteria**:
- File operations tracked
- Conflicts detected
- History retrievable
- Diff generation works

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/context/FileRegistry.kt`

---

### TASK-034: Context Module Integration
**Priority**: P1  
**Time**: 1h  
**Dependencies**: TASK-031, TASK-032  
**Assigned To**: Claude Code  
**Phase**: 3-Integration

**Description**: Integrate context management components

**Tasks**:
1. Create `modules/context/ContextModule.kt`:
   - Provide unified context access
   - Coordinate snapshots, memory, files
   - `getTaskContext(taskId): TaskContext`
   - `updateContext(taskId, updates)`

**Acceptance Criteria**:
- All context components accessible
- Context retrieval efficient
- Updates atomic
- Well tested

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/context/ContextModule.kt`

---

## Phase 4: MCP Server (External Interface)

### TASK-035: MCP Tool - create_consensus_task
**Priority**: P0  
**Time**: 2h  
**Dependencies**: TASK-023, TASK-012  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Implement create_consensus_task MCP tool

**Tasks**:
1. Create `mcp/tools/CreateConsensusTaskTool.kt`:
   - Parse tool parameters (with user directive fields)
   - Validate input
   - Call RoutingModule
   - Create task in repository
   - Return task ID and status
2. Add JSON schema definition

**Acceptance Criteria**:
- Tool callable via MCP
- Parameters validated
- Task created correctly
- Response structured properly
- User directives handled

**Files to Create**:
- `src/main/kotlin/com/orchestrator/mcp/tools/CreateConsensusTaskTool.kt`

---

### TASK-036: MCP Tool - create_simple_task
**Priority**: P0  
**Time**: 1h  
**Dependencies**: TASK-035  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Implement create_simple_task MCP tool

**Tasks**:
1. Create `mcp/tools/CreateSimpleTaskTool.kt`:
   - Similar to consensus task but solo routing
   - Support skipConsensus directive
   - Immediate execution flag

**Acceptance Criteria**:
- Solo tasks created correctly
- Skip consensus logic works
- Emergency bypass supported

**Files to Create**:
- `src/main/kotlin/com/orchestrator/mcp/tools/CreateSimpleTaskTool.kt`

---

### TASK-037: MCP Tool - assign_task
**Priority**: P1  
**Time**: 1h  
**Dependencies**: TASK-035  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Implement assign_task MCP tool for user-directed assignment

**Tasks**:
1. Create `mcp/tools/AssignTaskTool.kt`:
   - Parse targetAgent parameter
   - Validate agent exists and available
   - Create task assigned to specific agent
   - Set appropriate status

**Acceptance Criteria**:
- Agent assignment works
- Validates agent availability
- Task created with correct status
- Error handling for offline agents

**Files to Create**:
- `src/main/kotlin/com/orchestrator/mcp/tools/AssignTaskTool.kt`

---

### TASK-038: MCP Tool - get_pending_tasks
**Priority**: P0  
**Time**: 1h  
**Dependencies**: TASK-012  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Implement get_pending_tasks MCP tool

**Tasks**:
1. Create `mcp/tools/GetPendingTasksTool.kt`:
   - Query tasks by agent ID (with sensible default) and status
   - Format as list of task summaries
   - Include context preview
   - Sort by priority/creation time

**Acceptance Criteria**:
- Returns correct tasks for agent
- Summaries include key info
- Sorted appropriately
- Fast execution

**Files to Create**:
- `src/main/kotlin/com/orchestrator/mcp/tools/GetPendingTasksTool.kt`

---

### TASK-039: MCP Tool - submit_input
**Priority**: P0  
**Time**: 1h  
**Dependencies**: TASK-028  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Implement submit_input MCP tool

**Tasks**:
1. Create `mcp/tools/SubmitInputTool.kt`:
   - Accept proposal/input from agent
   - Validate task exists and expects input
   - Store via ProposalManager
   - Update task status
   - Notify waiting agents

**Acceptance Criteria**:
- Input stored correctly
- Task status updated
- Notifications sent
- Error handling robust

**Files to Create**:
- `src/main/kotlin/com/orchestrator/mcp/tools/SubmitInputTool.kt`

---

### TASK-040: MCP Tool - continue_task
**Priority**: P0  
**Time**: 1h  
**Dependencies**: TASK-034  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Implement continue_task MCP tool

**Tasks**:
1. Create `mcp/tools/ContinueTaskTool.kt`:
   - Fetch task by ID
   - Retrieve all proposals
   - Get full context
   - Return complete package
   - Update task status to IN_PROGRESS

**Acceptance Criteria**:
- Full context returned
- Includes all proposals
- Conversation history included
- Fast retrieval

**Files to Create**:
- `src/main/kotlin/com/orchestrator/mcp/tools/ContinueTaskTool.kt`

---

### TASK-041: MCP Tool - get_task_status
**Priority**: P1  
**Time**: 30min  
**Dependencies**: TASK-012  
**Assigned To**: Either  
**Phase**: 4-Enhancement

**Description**: Implement get_task_status MCP tool

**Tasks**:
1. Create `mcp/tools/GetTaskStatusTool.kt`:
   - Query task by ID
   - Return current status
   - Include status metadata
   - Fast response

**Acceptance Criteria**:
- Returns current status
- Includes relevant metadata
- Error for invalid task ID

**Files to Create**:
- `src/main/kotlin/com/orchestrator/mcp/tools/GetTaskStatusTool.kt`

---

### TASK-042: MCP Tool - complete_task
**Priority**: P1  
**Time**: 1h  
**Dependencies**: TASK-012, TASK-014  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Implement complete_task MCP tool

**Tasks**:
1. Create `mcp/tools/CompleteTaskTool.kt`:
   - Accept completion result
   - Update task status to COMPLETED
   - Record decision if consensus
   - Calculate token metrics
   - Store final artifacts

**Acceptance Criteria**:
- Task marked complete
- Results stored
- Metrics recorded
- Decision finalized

**Files to Create**:
- `src/main/kotlin/com/orchestrator/mcp/tools/CompleteTaskTool.kt`

---

### TASK-043: MCP Resources - Tasks
**Priority**: P2  
**Time**: 1h  
**Dependencies**: TASK-012  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Implement tasks:// MCP resource

**Tasks**:
1. Create `mcp/resources/TasksResource.kt`:
   - Support query parameters (status, agent, date range)
   - Return filtered task list
   - Support pagination
   - JSON formatted

**Acceptance Criteria**:
- Query filters work
- Pagination implemented
- Fast queries
- Standard MCP resource format

**Files to Create**:
- `src/main/kotlin/com/orchestrator/mcp/resources/TasksResource.kt`

---

### TASK-044: MCP Resources - Metrics
**Priority**: P2  
**Time**: 1h  
**Dependencies**: TASK-015  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Implement metrics:// MCP resource

**Tasks**:
1. Create `mcp/resources/MetricsResource.kt`:
   - Expose token usage metrics
   - Expose performance metrics
   - Support time range queries
   - Return aggregated data

**Acceptance Criteria**:
- Metrics accessible
- Time ranges work
- Aggregations correct
- JSON format

**Files to Create**:
- `src/main/kotlin/com/orchestrator/mcp/resources/MetricsResource.kt`

---

### TASK-045: MCP Server Implementation
**Priority**: P0  
**Time**: 2h  
**Dependencies**: TASK-035 through TASK-042  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Implement main MCP server with Ktor

**Tasks**:
1. Create `mcp/McpServerImpl.kt`:
   - Setup Ktor HTTP server
   - Register all tools
   - Register all resources
   - Handle MCP protocol messages
   - Error handling
   - Logging
2. Configure host and port from config

**Acceptance Criteria**:
- HTTP server starts successfully
- All tools registered
- MCP protocol handling correct
- Concurrent requests handled
- Errors returned properly

**Files to Create**:
- `src/main/kotlin/com/orchestrator/mcp/McpServerImpl.kt`

---

## Phase 5: Workflows & Orchestration

### TASK-046: Workflow Executor Interface
**Priority**: P1  
**Time**: 1h  
**Dependencies**: TASK-003  
**Assigned To**: Codex CLI  
**Phase**: 4-Enhancement

**Description**: Define workflow execution interface

**Tasks**:
1. Create `workflows/WorkflowExecutor.kt`:
   - Interface with `execute(task: Task): WorkflowResult`
   - `WorkflowResult` data class
   - Workflow state management
   - Checkpoint support

**Acceptance Criteria**:
- Clean interface design
- Supports different workflow types
- State management clear
- Result comprehensive

**Files to Create**:
- `src/main/kotlin/com/orchestrator/workflows/WorkflowExecutor.kt`

---

### TASK-047: Solo Workflow Implementation
**Priority**: P1  
**Time**: 1h  
**Dependencies**: TASK-046  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Implement solo workflow executor

**Tasks**:
1. Create `workflows/SoloWorkflow.kt`:
   - Execute task with single agent
   - Handle timeouts
   - Retry logic
   - Result storage

**Acceptance Criteria**:
- Single agent execution
- Timeout handling
- Retries on failure
- Results stored

**Files to Create**:
- `src/main/kotlin/com/orchestrator/workflows/SoloWorkflow.kt`

---

### TASK-048: Sequential Workflow Implementation
**Priority**: P1  
**Time**: 2h  
**Dependencies**: TASK-046  
**Assigned To**: Codex CLI  
**Phase**: 4-Enhancement

**Description**: Implement sequential (planner→implementer) workflow

**Tasks**:
1. Create `workflows/SequentialWorkflow.kt`:
   - Execute planning phase
   - Validate plan
   - Execute implementation phase
   - Pass context between phases
   - Support iteration

**Acceptance Criteria**:
- Phases execute in order
- Context passed correctly
- Iteration supported
- State tracked

**Files to Create**:
- `src/main/kotlin/com/orchestrator/workflows/SequentialWorkflow.kt`

---

### TASK-049: Consensus Workflow Implementation
**Priority**: P1  
**Time**: 2h  
**Dependencies**: TASK-046, TASK-030  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Implement consensus workflow executor

**Tasks**:
1. Create `workflows/ConsensusWorkflow.kt`:
   - Request proposals from agents
   - Collect asynchronously
   - Execute consensus strategy
   - Store decision
   - Return result

**Acceptance Criteria**:
- Multiple agents involved
- Async proposal collection
- Consensus executed
- Decision recorded

**Files to Create**:
- `src/main/kotlin/com/orchestrator/workflows/ConsensusWorkflow.kt`

---

### TASK-050: Orchestration Engine
**Priority**: P0  
**Time**: 2h  
**Dependencies**: TASK-047, TASK-049  
**Assigned To**: Codex CLI  
**Phase**: 4-Enhancement

**Description**: Implement main orchestration engine

**Tasks**:
1. Create `core/OrchestrationEngine.kt`:
   - Route tasks to workflows
   - Manage workflow lifecycle
   - Handle workflow errors
   - Coordinate modules (routing, consensus, context)
   - State machine for task states
   - Event publishing

**Acceptance Criteria**:
- All workflows accessible
- Routing works correctly
- State machine enforced
- Error handling robust
- Events published

**Files to Create**:
- `src/main/kotlin/com/orchestrator/core/OrchestrationEngine.kt`
- `src/test/kotlin/com/orchestrator/core/OrchestrationEngineTest.kt`

---

### TASK-051: State Machine Implementation
**Priority**: P1  
**Time**: 1h  
**Dependencies**: TASK-003  
**Assigned To**: Codex CLI  
**Phase**: 4-Enhancement

**Description**: Implement task state machine

**Tasks**:
1. Create `core/StateMachine.kt`:
   - Define valid state transitions
   - `transition(from: TaskStatus, to: TaskStatus): Boolean`
   - Validation logic
   - State history tracking

**Acceptance Criteria**:
- All valid transitions defined
- Invalid transitions rejected
- History tracked
- Thread-safe

**Files to Create**:
- `src/main/kotlin/com/orchestrator/core/StateMachine.kt`
- `src/test/kotlin/com/orchestrator/core/StateMachineTest.kt`

---

### TASK-052: Event Bus Implementation
**Priority**: P2  
**Time**: 1h  
**Dependencies**: TASK-001  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Implement in-process event bus

**Tasks**:
1. Create `core/EventBus.kt`:
   - Publish events
   - Subscribe to events
   - Event types (TaskCreated, ProposalSubmitted, etc.)
   - Async event handling
   - Coroutine-based

**Acceptance Criteria**:
- Events published
- Subscribers notified
- Async processing
- No blocking

**Files to Create**:
- `src/main/kotlin/com/orchestrator/core/EventBus.kt`

---

## Phase 6: Agent Implementations

### TASK-053: McpAgent Base Class
**Priority**: P0  
**Time**: 1h  
**Dependencies**: TASK-002  
**Assigned To**: Codex CLI  
**Phase**: 4-Enhancement

**Description**: Create base class for MCP-based agents

**Tasks**:
1. Create `agents/McpAgent.kt`:
   - Abstract base class
   - MCP connection handling
   - Common agent logic
   - Health check implementation

**Acceptance Criteria**:
- Reusable base class
- MCP protocol handling
- Easy to extend
- Well documented

**Files to Create**:
- `src/main/kotlin/com/orchestrator/agents/McpAgent.kt`

---

### TASK-054: Claude Code Agent Implementation
**Priority**: P0  
**Time**: 2h  
**Dependencies**: TASK-053, TASK-020  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Implement Claude Code agent

**Tasks**:
1. Create `agents/ClaudeCodeAgent.kt`:
   - Extend McpAgent
   - Define capabilities (CODE_GENERATION, REFACTORING, etc.)
   - Define strengths
   - Connection configuration
2. Create `agents/factories/ClaudeCodeAgentFactory.kt`
3. Register in META-INF/services

**Acceptance Criteria**:
- Agent connects to orchestrator
- Capabilities defined
- Factory creates agent
- SPI registration works

**Files to Create**:
- `src/main/kotlin/com/orchestrator/agents/ClaudeCodeAgent.kt`
- `src/main/kotlin/com/orchestrator/agents/factories/ClaudeCodeAgentFactory.kt`
- `src/main/resources/META-INF/services/com.orchestrator.core.AgentFactory`

---

### TASK-055: Codex CLI Agent Implementation
**Priority**: P0  
**Time**: 2h  
**Dependencies**: TASK-053, TASK-020  
**Assigned To**: Codex CLI  
**Phase**: 4-Enhancement

**Description**: Implement Codex CLI agent

**Tasks**:
1. Create `agents/CodexCLIAgent.kt`:
   - Extend McpAgent
   - Define capabilities (ARCHITECTURE, REASONING, etc.)
   - Define strengths
   - Connection configuration
2. Create `agents/factories/CodexCLIAgentFactory.kt`
3. Add to SPI registry

**Acceptance Criteria**:
- Agent connects
- Capabilities defined
- Factory works
- Registered via SPI

**Files to Create**:
- `src/main/kotlin/com/orchestrator/agents/CodexCLIAgent.kt`
- `src/main/kotlin/com/orchestrator/agents/factories/CodexCLIAgentFactory.kt`

---

### TASK-056: Placeholder Agent Implementations
**Priority**: P3  
**Time**: 2h  
**Dependencies**: TASK-053  
**Assigned To**: Either  
**Phase**: 4-Enhancement

**Description**: Create placeholder implementations for future agents

**Tasks**:
1. Create placeholder classes:
   - `agents/GeminiAgent.kt`
   - `agents/QwenAgent.kt`
   - `agents/DeepSeekCoderAgent.kt`
2. Mark as disabled in config
3. Add TODO comments for implementation

**Acceptance Criteria**:
- Classes compile
- Clear TODO markers
- Disabled by default
- Documentation present

**Files to Create**:
- `src/main/kotlin/com/orchestrator/agents/GeminiAgent.kt`
- `src/main/kotlin/com/orchestrator/agents/QwenAgent.kt`
- `src/main/kotlin/com/orchestrator/agents/DeepSeekCoderAgent.kt`

---

## Phase 7: Metrics & Monitoring

### TASK-057: Token Tracker
**Priority**: P1  
**Time**: 1h  
**Dependencies**: TASK-015, TASK-016  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Implement token usage tracking

**Tasks**:
1. Create `modules/metrics/TokenTracker.kt`:
   - Track tokens per task
   - Track tokens per agent
   - Calculate savings
   - Alert on budget limits
   - Generate reports

**Acceptance Criteria**:
- Tokens tracked accurately
- Savings calculated
- Alerts triggered
- Reports generated

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/metrics/TokenTracker.kt`

---

### TASK-058: Performance Monitor
**Priority**: P2  
**Time**: 1h  
**Dependencies**: TASK-015  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Implement performance monitoring

**Tasks**:
1. Create `modules/metrics/PerformanceMonitor.kt`:
   - Track task completion times
   - Track agent response times
   - Calculate success rates
   - Identify bottlenecks
   - Generate dashboards

**Acceptance Criteria**:
- Metrics collected
- Bottlenecks identified
- Dashboards available
- Historical trends

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/metrics/PerformanceMonitor.kt`

---

### TASK-059: Decision Analytics
**Priority**: P2  
**Time**: 2h  
**Dependencies**: TASK-014  
**Assigned To**: Codex CLI  
**Phase**: 4-Enhancement

**Description**: Analyze routing and consensus decision quality

**Tasks**:
1. Create `modules/metrics/DecisionAnalytics.kt`:
   - Track routing accuracy
   - Measure confidence vs outcome
   - Identify optimal strategies
   - Learning from patterns
   - Generate reports

**Acceptance Criteria**:
- Decision quality tracked
- Patterns identified
- Reports actionable
- Learning applied

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/metrics/DecisionAnalytics.kt`

---

### TASK-060: Alert System
**Priority**: P2  
**Time**: 1h  
**Dependencies**: TASK-052  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Implement alerting system

**Tasks**:
1. Create `modules/metrics/AlertSystem.kt`:
   - Define alert types
   - Configurable thresholds
   - Alert delivery via events
   - Alert history
   - Alert acknowledgment

**Acceptance Criteria**:
- Alerts generated correctly
- Thresholds configurable
- Delivery reliable
- History queryable

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/metrics/AlertSystem.kt`

---

### TASK-061: Metrics Module Integration
**Priority**: P2  
**Time**: 1h  
**Dependencies**: TASK-057, TASK-058  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Integrate metrics components

**Tasks**:
1. Create `modules/metrics/MetricsModule.kt`:
   - Coordinate all metrics components
   - Unified metrics access
   - Periodic aggregation
   - Buffer management

**Acceptance Criteria**:
- All metrics accessible
- Aggregation scheduled
- Buffer efficient
- Well tested

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/metrics/MetricsModule.kt`

---

## Phase 8: Main Application & Testing

### TASK-062: Logger Utility
**Priority**: P0  
**Time**: 30min  
**Dependencies**: TASK-001  
**Assigned To**: Either  
**Phase**: 4-Enhancement

**Description**: Setup structured logging

**Tasks**:
1. Create `utils/Logger.kt`:
   - Wrapper around logging framework
   - Structured logging support
   - Correlation IDs
   - Log levels
2. Configure logback.xml

**Acceptance Criteria**:
- Logging works
- Structured format
- Correlation IDs included
- Configurable levels

**Files to Create**:
- `src/main/kotlin/com/orchestrator/utils/Logger.kt`
- `src/main/resources/logback.xml`

---

### TASK-063: ID Generator Utility
**Priority**: P1  
**Time**: 30min  
**Dependencies**: TASK-001  
**Assigned To**: Either  
**Phase**: 4-Enhancement

**Description**: Create ID generation utility

**Tasks**:
1. Create `utils/IdGenerator.kt`:
   - Generate unique IDs for tasks, proposals, etc.
   - Use UUID or similar
   - Collision-free
   - Fast generation

**Acceptance Criteria**:
- IDs unique
- Fast generation
- String format
- Easy to use

**Files to Create**:
- `src/main/kotlin/com/orchestrator/utils/IdGenerator.kt`

---

### TASK-064: Application Configuration Loader
**Priority**: P0  
**Time**: 1h  
**Dependencies**: TASK-011  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Load application.conf and agents.toml

**Tasks**:
1. Update `config/ConfigLoader.kt`:
   - Load HOCON application.conf
   - Load TOML agents.toml
   - Merge configurations
   - Validate all settings
   - Environment variable support

**Acceptance Criteria**:
- Both config files loaded
- Validation works
- Environment vars resolved
- Errors clear

**Files to Create**:
- `src/main/resources/application.conf`

---

### TASK-065: Main Application Entry Point
**Priority**: P0  
**Time**: 2h  
**Dependencies**: TASK-050, TASK-045, TASK-064  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Implement main application startup

**Tasks**:
1. Create `Main.kt`:
   - Load configurations
   - Initialize database
   - Initialize all modules
   - Start MCP server
   - Setup shutdown hooks
   - Error handling
2. Add CLI argument parsing

**Acceptance Criteria**:
- Application starts successfully
- All components initialized
- Graceful shutdown
- Error handling robust
- CLI args work

**Files to Create**:
- `src/main/kotlin/com/orchestrator/Main.kt`

---

### TASK-066: Integration Test - Simple Task
**Priority**: P1  
**Time**: 2h  
**Dependencies**: TASK-065  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: End-to-end test for simple task workflow

**Tasks**:
1. Create integration test:
   - Start orchestrator
   - Create simple task via MCP
   - Verify routing
   - Verify execution
   - Verify result storage
2. Use test containers if needed

**Acceptance Criteria**:
- Test passes
- Full workflow covered
- Database verified
- Cleanup works

**Files to Create**:
- `src/test/kotlin/com/orchestrator/integration/SimpleTaskIntegrationTest.kt`

---

### TASK-067: Integration Test - Consensus Task
**Priority**: P1  
**Time**: 2h  
**Dependencies**: TASK-066  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: End-to-end test for consensus workflow

**Tasks**:
1. Create integration test:
   - Create consensus task
   - Simulate multiple agents
   - Submit proposals
   - Verify consensus execution
   - Verify decision storage

**Acceptance Criteria**:
- Test passes
- Multiple agents simulated
- Consensus reached
- Decision recorded

**Files to Create**:
- `src/test/kotlin/com/orchestrator/integration/ConsensusTaskIntegrationTest.kt`

---

### TASK-068: Integration Test - User Directives
**Priority**: P1  
**Time**: 2h  
**Dependencies**: TASK-067  
**Assigned To**: Codex CLI  
**Phase**: 4-Enhancement

**Description**: Test user directive handling end-to-end

**Tasks**:
1. Create integration test:
   - Test force consensus directive
   - Test prevent consensus directive
   - Test agent assignment directive
   - Test emergency bypass
   - Verify routing decisions

**Acceptance Criteria**:
- All directives tested
- Routing respects directives
- Overrides work correctly
- Logging verified

**Files to Create**:
- `src/test/kotlin/com/orchestrator/integration/UserDirectiveIntegrationTest.kt`

---

### TASK-069: Documentation - README
**Priority**: P1  
**Time**: 1h  
**Dependencies**: TASK-065  
**Assigned To**: Either  
**Phase**: 4-Enhancement

**Description**: Create comprehensive README

**Tasks**:
1. Update `README.md`:
   - Project overview
   - Quick start guide
   - Building instructions
   - Configuration guide
   - Usage examples
   - Troubleshooting

**Acceptance Criteria**:
- Clear instructions
- Examples work
- Covers all features
- Well formatted

**Files to Update**:
- `README.md`

---

### TASK-070: Documentation - API Reference
**Priority**: P2  
**Time**: 2h  
**Dependencies**: TASK-065  
**Assigned To**: Either  
**Phase**: 4-Enhancement

**Description**: Create MCP API reference documentation

**Tasks**:
1. Create `docs/API_REFERENCE.md`:
   - Document all MCP tools
   - Document all MCP resources
   - Include examples
   - Parameter schemas
   - Error codes

**Acceptance Criteria**:
- All tools documented
- Examples provided
- Schemas complete
- Easy to follow

**Files to Create**:
- `docs/API_REFERENCE.md`

---

## Phase 9: Polish & Optimization

### TASK-071: Performance Optimization - Database Indexes
**Priority**: P2  
**Time**: 1h  
**Dependencies**: TASK-007  
**Assigned To**: Codex CLI  
**Phase**: 4-Enhancement

**Description**: Optimize database queries with indexes

**Tasks**:
1. Analyze slow queries
2. Add indexes for:
   - task status + agent_id
   - task created_at
   - proposal task_id
   - metrics timestamp
3. Test query performance

**Acceptance Criteria**:
- Queries faster
- Indexes created
- No performance regression

**Files to Update**:
- `src/main/kotlin/com/orchestrator/storage/schema/Schema.kt`

---

### TASK-072: Error Handling Improvement
**Priority**: P2  
**Time**: 2h  
**Dependencies**: TASK-065  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Improve error handling across the application

**Tasks**:
1. Review all error paths
2. Add specific exception types
3. Improve error messages
4. Add error recovery logic
5. Log errors appropriately

**Acceptance Criteria**:
- Errors handled gracefully
- Messages clear
- Recovery when possible
- Proper logging

**Files to Update**:
- Various (create exception classes)

---

### TASK-073: Configuration Validation Enhancement
**Priority**: P2  
**Time**: 1h  
**Dependencies**: TASK-064  
**Assigned To**: Either  
**Phase**: 4-Enhancement

**Description**: Improve configuration validation

**Tasks**:
1. Add comprehensive validation rules
2. Check value ranges
3. Validate references
4. Better error messages
5. Validation tests

**Acceptance Criteria**:
- Invalid configs rejected
- Messages helpful
- All fields validated
- Tests pass

**Files to Update**:
- `src/main/kotlin/com/orchestrator/config/ConfigLoader.kt`

---

### TASK-074: Logging Enhancement
**Priority**: P2  
**Time**: 1h  
**Dependencies**: TASK-062  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Improve logging throughout application

**Tasks**:
1. Add structured logging fields
2. Include correlation IDs everywhere
3. Add timing information
4. Improve log messages
5. Add debug logging

**Acceptance Criteria**:
- Logs informative
- Structured format
- Correlation works
- Debug mode useful

**Files to Update**:
- Various (add logging calls)

---

### TASK-075: Health Check Endpoint
**Priority**: P2  
**Time**: 1h  
**Dependencies**: TASK-045  
**Assigned To**: Claude Code  
**Phase**: 4-Enhancement

**Description**: Add health check endpoint

**Tasks**:
1. Add `/health` endpoint
2. Check database connection
3. Check agent availability
4. Return status summary
5. Include version info

**Acceptance Criteria**:
- Endpoint works
- Status accurate
- Fast response
- Useful for monitoring

**Files to Update**:
- `src/main/kotlin/com/orchestrator/mcp/McpServerImpl.kt`

---

## Summary Statistics

### Total Tasks: 75

### By Priority:
- **P0 (Critical)**: 26 tasks
- **P1 (High)**: 29 tasks
- **P2 (Medium)**: 18 tasks
- **P3 (Low)**: 2 tasks

### By Phase:
- **Phase 1 (Foundation)**: 11 tasks
- **Phase 2 (Core)**: 12 tasks
- **Phase 3 (Integration)**: 11 tasks
- **Phase 4 (Enhancement)**: 30 tasks
- **Phase 5 (Polish)**: 5 tasks

### By Agent Assignment:
- **Claude Code**: 31 tasks
- **Codex CLI**: 17 tasks
- **Either**: 27 tasks

### Estimated Total Time:
- **Minimum**: ~85 hours (individual task times)
- **With dependencies and testing**: ~120-150 hours

---

## Execution Strategy

### Week 1: Foundation
- Complete all P0 tasks in Phase 1
- Setup project, domain models, database

### Week 2: Core Components
- Complete Phase 2 (repositories, routing, parsing)
- Start Phase 3 (consensus)

### Week 3: Integration
- Complete Phase 3 (consensus, context)
- Start Phase 4 (MCP tools)

### Week 4: MCP & Workflows
- Complete MCP server implementation
- Implement workflows
- Agent implementations

### Week 5: Testing & Polish
- Integration tests
- Documentation
- Performance optimization

---

## Success Criteria

✅ All P0 and P1 tasks completed  
✅ Core workflows functional (solo, consensus, sequential)  
✅ MCP server operational  
✅ Both agents (Claude Code, Codex CLI) working  
✅ Integration tests passing  
✅ Documentation complete  
✅ User directives working  
✅ Token tracking operational  

---

**End of Implementation Plan**
