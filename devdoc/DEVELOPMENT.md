# Development Guide

This document provides technical details for developers working on the Agent Fusion orchestration system.

## Table of Contents

1. [Project Structure](#project-structure)
2. [Development Setup](#development-setup)
3. [Building and Running](#building-and-running)
4. [Testing](#testing)
5. [Architecture Details](#architecture-details)
6. [Code Organization](#code-organization)
7. [Contributing](#contributing)

---

## Project Structure

```
agent-fusion/
│
├── build.gradle.kts              # Gradle build configuration
├── settings.gradle.kts           # Gradle settings
├── README.md                     # User-facing documentation
│
├── config/
│   ├── agents.toml               # Agent configurations
│   └── application.conf          # Server configuration
│
├── docs/                         # Documentation
│   ├── API_REFERENCE.md          # MCP API documentation
│   ├── DEVELOPMENT.md            # This file
│   ├── INSTALL.md                # Installation guide
│   ├── SEQUENCE_DIAGRAMS.md      # Workflow visualizations
│   ├── CONVERSATION_HANDOFF_WORKFLOW.md
│   └── AGENT_ORCHESTRATOR_INSTRUCTIONS.md
│
├── src/
│   ├── main/
│   │   ├── kotlin/
│   │   │   └── com/orchestrator/
│   │   │       │
│   │   │       ├── Main.kt       # Application entry point
│   │   │       │
│   │   │       ├── config/       # Configuration loading
│   │   │       │   ├── Configuration.kt
│   │   │       │   └── ConfigLoader.kt
│   │   │       │
│   │   │       ├── core/         # Core components
│   │   │       │   ├── AgentFactory.kt           # Factory interface
│   │   │       │   ├── AgentFactoryRegistry.kt   # SPI-based registry
│   │   │       │   ├── AgentRegistry.kt          # Active agents
│   │   │       │   ├── OrchestrationEngine.kt    # Main coordinator
│   │   │       │   ├── EventBus.kt               # Event system
│   │   │       │   └── StateMachine.kt           # Workflow states
│   │   │       │
│   │   │       ├── domain/       # Domain models
│   │   │       │   ├── Agent.kt
│   │   │       │   ├── Task.kt
│   │   │       │   ├── Proposal.kt
│   │   │       │   ├── Decision.kt
│   │   │       │   ├── Context.kt
│   │   │       │   ├── Metrics.kt
│   │   │       │   └── RoutingDecision.kt
│   │   │       │
│   │   │       ├── agents/       # Agent implementations
│   │   │       │   ├── ClaudeCodeAgent.kt
│   │   │       │   ├── CodexCLIAgent.kt
│   │   │       │   ├── AmazonQAgent.kt
│   │   │       │   ├── GeminiAgent.kt
│   │   │       │   │
│   │   │       │   └── factories/                # Agent factories (SPI)
│   │   │       │       ├── ClaudeCodeAgentFactory.kt
│   │   │       │       ├── CodexCLIAgentFactory.kt
│   │   │       │       ├── AmazonQAgentFactory.kt
│   │   │       │       └── GeminiAgentFactory.kt
│   │   │       │
│   │   │       ├── modules/      # Feature modules
│   │   │       │   │
│   │   │       │   ├── routing/  # Task routing logic
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
│   │   │       │   │   │
│   │   │       │   │   └── strategies/
│   │   │       │   │       ├── ConsensusStrategy.kt
│   │   │       │   │       ├── VotingStrategy.kt
│   │   │       │   │       ├── ReasoningQualityStrategy.kt
│   │   │       │   │       ├── MergeStrategy.kt
│   │   │       │   │       └── TokenOptimizationStrategy.kt
│   │   │       │   │
│   │   │       │   ├── context/  # Shared context
│   │   │       │   │   ├── ContextModule.kt
│   │   │       │   │   ├── MemoryManager.kt
│   │   │       │   │   ├── StateManager.kt
│   │   │       │   │   ├── FileRegistry.kt
│   │   │       │   │   └── ArtifactStore.kt
│   │   │       │   │
│   │   │       │   └── metrics/  # Token tracking & analytics
│   │   │       │       ├── MetricsModule.kt
│   │   │       │       ├── TokenTracker.kt
│   │   │       │       ├── PerformanceMonitor.kt
│   │   │       │       ├── DecisionAnalytics.kt
│   │   │       │       └── AlertSystem.kt
│   │   │       │
│   │   │       ├── mcp/          # MCP server implementation
│   │   │       │   ├── McpServerImpl.kt          # Server core
│   │   │       │   │
│   │   │       │   ├── tools/    # MCP tools exposed to agents
│   │   │       │   │   ├── CreateConsensusTaskTool.kt
│   │   │       │   │   ├── CreateSimpleTaskTool.kt
│   │   │       │   │   ├── AssignTaskTool.kt
│   │   │       │   │   ├── GetPendingTasksTool.kt
│   │   │       │   │   ├── SubmitInputTool.kt
│   │   │       │   │   ├── GetTaskStatusTool.kt
│   │   │       │   │   ├── ContinueTaskTool.kt
│   │   │       │   │   ├── RespondToTaskTool.kt
│   │   │       │   │   └── CompleteTaskTool.kt
│   │   │       │   │
│   │   │       │   └── resources/                # MCP resources
│   │   │       │       ├── TasksResource.kt
│   │   │       │       ├── ProposalsResource.kt
│   │   │       │       ├── ContextResource.kt
│   │   │       │       └── MetricsResource.kt
│   │   │       │
│   │   │       ├── workflows/    # Workflow orchestration
│   │   │       │   ├── BaseWorkflowExecutor.kt
│   │   │       │   ├── WorkflowExecutor.kt
│   │   │       │   ├── SoloWorkflow.kt
│   │   │       │   ├── SequentialWorkflow.kt
│   │   │       │   ├── ConsensusWorkflow.kt
│   │   │       │   ├── ParallelWorkflow.kt       # NEW in v1.5
│   │   │       │   └── AdaptiveWorkflow.kt
│   │   │       │
│   │   │       ├── storage/      # Database and repositories
│   │   │       │   ├── Database.kt               # Connection manager
│   │   │       │   ├── Transaction.kt            # Transaction helper
│   │   │       │   │
│   │   │       │   ├── repositories/             # Raw JDBC repositories
│   │   │       │   │   ├── TaskRepository.kt
│   │   │       │   │   ├── ProposalRepository.kt
│   │   │       │   │   ├── DecisionRepository.kt
│   │   │       │   │   ├── MessageRepository.kt
│   │   │       │   │   ├── MetricsRepository.kt
│   │   │       │   │   └── ContextSnapshotRepository.kt
│   │   │       │   │
│   │   │       │   └── schema/
│   │   │       │       └── Schema.kt             # SQL DDL statements
│   │   │       │
│   │   │       └── utils/        # Utilities
│   │   │           ├── Logger.kt
│   │   │           ├── TokenEstimator.kt
│   │   │           ├── IdGenerator.kt
│   │   │           └── Duration.kt
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
│               │   ├── SoloWorkflowTest.kt
│               │   ├── ConsensusWorkflowTest.kt
│               │   ├── ParallelWorkflowTest.kt    # NEW in v1.5
│               │   └── WorkflowIntegrationTest.kt
│               │
│               └── integration/
│                   └── McpServerIntegrationTest.kt
│
└── data/
    └── orchestrator.duckdb       # Created at runtime
```

---

## Development Setup

### Prerequisites

- **JDK 17+** (recommended: JDK 21)
- **Gradle 8.5+** (wrapper included)
- **Kotlin 2.0+**

### Environment Setup

1. **Clone the repository**:
   ```bash
   git clone <repository-url>
   cd agent-fusion
   ```

2. **Build the project**:
   ```bash
   ./gradlew build
   ```

3. **Configure agents**:
   Edit `config/agents.toml` to enable/disable agents and set connection details.

4. **Set environment variables** (if using API-based agents):
   ```bash
   export GEMINI_API_KEY="your-api-key"
   export QWEN_API_KEY="your-api-key"
   ```

---

## Building and Running

### Build Commands

```bash
# Clean build
./gradlew clean build

# Build without tests
./gradlew build -x test

# Run tests only
./gradlew test

# Generate fat JAR
./gradlew shadowJar
```

### Running the Server

```bash
# Run from source
./gradlew run

# Run fat JAR
java -jar build/libs/orchestrator-all.jar

# Run with custom config
java -jar build/libs/orchestrator-all.jar --config=custom.conf
```

### Development Mode

```bash
# Auto-reload on changes (requires gradle continuous build)
./gradlew run --continuous
```

---

## Testing

### Test Structure

- **Unit Tests**: Test individual components in isolation
- **Integration Tests**: Test component interactions
- **Workflow Tests**: End-to-end workflow testing
- **Database Tests**: Repository and SQL query testing

### Running Tests

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "ParallelWorkflowTest"

# Run tests with logging
./gradlew test --info

# Run tests with coverage
./gradlew test jacocoTestReport
```

### Test Database

Tests use an in-memory DuckDB instance that is created and destroyed for each test suite.

### Writing Tests

Example test structure:

```kotlin
class ParallelWorkflowTest {
    private lateinit var workflow: ParallelWorkflow
    private lateinit var agentRegistry: AgentRegistry

    @BeforeEach
    fun setup() {
        agentRegistry = AgentRegistry()
        workflow = ParallelWorkflow(agentRegistry)
    }

    @Test
    fun `should execute agents in parallel`() = runBlocking {
        // Given
        val task = Task(...)

        // When
        val result = workflow.execute(runtime)

        // Then
        assertThat(result).isInstanceOf<WorkflowStep.Success>()
    }
}
```

---

## Architecture Details

### Technology Stack

- **Language**: Kotlin 2.0+ (JVM)
- **Build**: Gradle Kotlin DSL
- **Concurrency**: Kotlin Coroutines & Flows
- **Serialization**: kotlinx.serialization
- **MCP**: `io.github.modelcontextprotocol:kotlin-sdk`
- **HTTP Server**: Ktor (for MCP HTTP transport)
- **Database**: DuckDB (JDBC)
- **Configuration**: Typesafe Config (HOCON)
- **Logging**: SLF4J + Logback

### Design Principles

1. **Plugin Architecture**: Agents are discovered via Java SPI (Service Provider Interface)
2. **Event-Driven**: Components communicate via EventBus
3. **Coroutine-First**: All I/O operations are suspending functions
4. **Immutable Domain Models**: All domain objects are immutable data classes
5. **Raw JDBC**: Direct SQL control, no ORM overhead
6. **Configuration-Driven**: Agent management through TOML configuration

### Core Components

#### AgentRegistry
Maintains active agents and provides lookup by ID, type, or capability.

#### OrchestrationEngine
Main coordinator that:
- Registers workflows
- Routes tasks to appropriate workflow
- Manages workflow execution lifecycle

#### EventBus
Async pub/sub system for decoupled component communication:
- `TaskCreated`
- `ProposalSubmitted`
- `ConsensusReached`
- `WorkflowCompleted`

#### Workflow Executors
- **SoloWorkflow**: Single-agent execution
- **ConsensusWorkflow**: Multi-agent collaboration with voting
- **SequentialWorkflow**: Phased execution with handoffs
- **ParallelWorkflow**: Simultaneous agent execution (v1.5)

---

## Code Organization

### Module Structure

The codebase is organized into feature modules:

- **core/**: Essential system components (registry, engine, events)
- **domain/**: Business entities and value objects
- **agents/**: Agent implementations and factories
- **modules/**: Feature-specific logic (routing, consensus, metrics, context)
- **workflows/**: Workflow orchestration strategies
- **mcp/**: MCP server and tool implementations
- **storage/**: Database access layer
- **utils/**: Cross-cutting utilities

### Naming Conventions

- **Interfaces**: Noun (e.g., `Agent`, `WorkflowExecutor`)
- **Implementations**: NounImpl or descriptive name (e.g., `ClaudeCodeAgent`)
- **Data Classes**: Noun (e.g., `Task`, `Proposal`)
- **Functions**: Verb phrase (e.g., `executeWorkflow`, `submitProposal`)
- **Suspend Functions**: Same as functions (Kotlin convention)

### Package Guidelines

```kotlin
// Domain models - pure data, no dependencies
package com.orchestrator.domain

// Core abstractions - minimal dependencies
package com.orchestrator.core

// Feature modules - depend on domain + core
package com.orchestrator.modules.routing
package com.orchestrator.modules.consensus

// Infrastructure - depend on everything
package com.orchestrator.storage
package com.orchestrator.mcp
```

---

## Contributing

### Development Workflow

1. **Create feature branch**: `git checkout -b feature/your-feature`
2. **Make changes**: Follow code style and conventions
3. **Write tests**: Ensure new code has test coverage
4. **Run tests**: `./gradlew test`
5. **Commit changes**: Use descriptive commit messages
6. **Push and create PR**: Push branch and open pull request

### Code Style

- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use 4 spaces for indentation
- Maximum line length: 120 characters
- Use trailing commas in multi-line declarations

### Adding a New Agent Type

**Example: Adding support for a new AI agent**

1. **Create agent implementation**:
```kotlin
// agents/NewAgent.kt
class NewAgent(config: AgentConfig) : Agent {
    override val id: AgentId = AgentId(config.id)
    override val type: AgentType = AgentType.NEW_AGENT
    // ... implementation
}
```

2. **Create factory**:
```kotlin
// agents/factories/NewAgentFactory.kt
class NewAgentFactory : AgentFactory {
    override val supportedType = "NEW_AGENT"

    override fun createAgent(config: AgentConfig): Agent {
        return NewAgent(config)
    }
}
```

3. **Register via SPI**:
```
# META-INF/services/com.orchestrator.core.AgentFactory
com.orchestrator.agents.factories.NewAgentFactory
```

4. **Add configuration**:
```toml
[[agents]]
id = "new-agent"
type = "NEW_AGENT"
enabled = true
# ... config
```

### Database Changes

When modifying the database schema:

1. Update `Schema.kt` with new DDL
2. Update repository classes
3. Consider migration strategy for existing databases
4. Update tests

### Adding MCP Tools

1. Create tool class in `mcp/tools/`
2. Implement `McpTool` interface
3. Register in `McpServerImpl`
4. Add documentation to API_REFERENCE.md
5. Write integration tests

---

## Additional Resources

- [API Reference](API_REFERENCE.md) - Complete MCP API documentation
- [Sequence Diagrams](SEQUENCE_DIAGRAMS.md) - Visual workflow documentation
- [Installation Guide](INSTALL.md) - Setup instructions for all agents
- [Agent Orchestrator Instructions](AGENT_ORCHESTRATOR_INSTRUCTIONS.md) - Agent configuration guide

---

**Generated**: 2025-10-11
**Version**: 1.0
