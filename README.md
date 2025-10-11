# Multi-Agent Orchestrator

A multi-agent orchestration system that enables Claude Code, Codex CLI, Amazon Q Developer, and Gemini Code Assist to collaborate bidirectionally through intelligent task routing and consensus-based decision making.

## Architecture Overview

### Core Concept

The system enables multiple AI agents (Claude Code, Codex CLI, Amazon Q Developer, Gemini Code Assist) to collaborate on complex tasks through a central MCP (Model Context Protocol) server that maintains shared context and orchestrates their interactions.

```
┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│              │  │              │  │              │  │              │
│ Claude Code  │  │  Codex CLI   │  │  Amazon Q    │  │   Gemini     │
│  (Agent 1)   │  │  (Agent 2)   │  │  (Agent 3)   │  │  (Agent 4)   │
│              │  │              │  │              │  │              │
└──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘
       │                 │                 │                 │
       │     MCP Client Connections (bidirectional)          │
       │                 │                 │                 │
       └─────────────────┼─────────────────┼─────────────────┘
                         ▼                 ▼
    ┌─────────────────────────────────────────────────────────────┐
    │                                                             │
    │                 MCP Orchestrator Server                     │
    │                                                             │
    │  ┌───────────────────────────────────────────────────────┐  │
    │  │         Shared Context & Task Queue                   │  │
    │  │  • Task routing & assignment                          │  │
    │  │  • Proposals & consensus voting                       │  │
    │  │  • Multi-agent conversation history                   │  │
    │  └───────────────────────────────────────────────────────┘  │
    │                                                             │
    │  ┌───────────────────────────────────────────────────────┐  │
    │  │          Persistent Storage (DuckDB)                  │  │
    │  │  • Tasks, Proposals, Decisions                        │  │
    │  │  • Agent metrics & performance                        │  │
    │  │  • Context snapshots                                  │  │
    │  └───────────────────────────────────────────────────────┘  │
    │                                                             │
    └─────────────────────────────────────────────────────────────┘
```

### How It Works

1. **Agents Connect**: Multiple AI agents (Claude Code, Codex CLI, Amazon Q Developer, Gemini Code Assist) connect to the MCP server as clients
2. **Task Creation**: Any agent can create tasks (simple, consensus, or assigned)
3. **Context Sharing**: The server maintains shared context visible to all agents
4. **Collaboration**: Agents submit proposals, vote on solutions, and review each other's work
5. **Routing**: Intelligent routing decides whether tasks need single-agent or multi-agent collaboration

**Key Benefits:**
- **Multi-Agent Support**: Works with Claude Code, Codex CLI, Amazon Q Developer, and Gemini Code Assist
- **Bidirectional**: All agents can initiate tasks and respond to each other
- **Context Preservation**: Full conversation history and task context maintained centrally
- **Flexible Workflows**: Supports solo, consensus, sequential, and parallel execution modes

## Features

🎥 **[Watch Demo Video](https://youtu.be/kXkTh0fJ0Lc)** - See consensus collaboration in action

### What Makes This Unique

- **True Bidirectional Collaboration**: All agents can initiate tasks, respond to each other, and manage workflows - not just sequential handoffs
- **Multi-Agent Support**: Works with Claude Code, Codex CLI, Amazon Q Developer, and Gemini Code Assist
- **Consensus-Based Decision Making**: Multiple agents propose solutions and vote on the best approach for critical architectural and security decisions
- **Intelligent Task Routing**: Automatically analyzes task complexity and risk to select optimal execution approach - supports solo execution for simple tasks and multi-agent consensus for critical decisions
- **Persistent Task Queue**: Agents check pending work assigned to them, enabling asynchronous collaboration across sessions
- **Flexible Workflow Control**: Supports solo execution, multi-agent consensus, direct assignments, and emergency bypass modes
- **Event-Driven Architecture**: Async event bus enables decoupled, scalable component communication

## Installation

**No API keys required** - All agents (Claude Code, Codex CLI, Amazon Q Developer, Gemini Code Assist) run using your existing local installations and connect via MCP.

See [Installation Guide](docs/INSTALL.md) for setup instructions for all supported agents.

## Getting Started

### Agent Configuration

For optimal collaboration, it's **highly recommended** to provide the [Agent Orchestrator Instructions](docs/AGENT_ORCHESTRATOR_INSTRUCTIONS.md) to your AI agents before starting work. This enables agents to:

- Understand how to create and manage tasks
- Detect user routing directives from natural language
- Follow proper handoff workflows between agents
- Use MCP tools correctly for collaboration

**Recommended approach:**

In your first message to the agent, say:
```
"Read and follow the instructions in docs/AGENT_ORCHESTRATOR_INSTRUCTIONS.md"
```

Or include the content as context at the start of your session.

### Usage Examples

For detailed workflow examples, see [Conversation Handoff Workflow](docs/CONVERSATION_HANDOFF_WORKFLOW.md).

## MCP Server Endpoints

### Health Check
```
GET /healthz
```

### Tools
```
GET /mcp/tools              # List available tools
POST /mcp/tools/call        # Invoke a tool
```

**Available Tools:**
- `create_simple_task` - Create a single-agent task
- `create_consensus_task` - Create a multi-agent consensus task
- `assign_task` - Assign task to specific agent
- `continue_task` - Load task context for continuation
- `respond_to_task` - Load task context and submit response in one operation (recommended)
- `complete_task` - Mark task as completed
- `get_pending_tasks` - Get pending tasks for an agent
- `get_task_status` - Get task status
- `submit_input` - Submit agent input/proposal

## Project Structure

- `src/main/kotlin/com/orchestrator/`
  - `Main.kt` - Application entry point
  - `config/` - Configuration loading
  - `core/` - Core components (AgentRegistry, EventBus)
  - `domain/` - Domain models (Task, Agent, Proposal, Decision)
  - `modules/` - Feature modules (routing, consensus, metrics, MCP)
  - `storage/` - Database and repositories
  - `utils/` - Utilities (Logger, IdGenerator)
- `src/test/kotlin` - Tests
- `config/` - Configuration files
- `build.gradle.kts` - Gradle build script



## Architecture

### Core Components

- **Routing Module**: Classifies tasks and selects optimal routing strategy
  - SOLO: Single agent execution
  - CONSENSUS: Multiple agents collaborate
  - SEQUENTIAL: Agents work in sequence
  - PARALLEL: Agents work in parallel

- **Consensus Module**: Coordinates multi-agent collaboration
  - Voting Strategy: Democratic voting
  - Reasoning Quality: Best reasoning wins
  - Token Optimization: Minimize token usage

- **Metrics Module**: Comprehensive tracking
  - Token usage per task/agent
  - Performance monitoring
  - Decision analytics
  - Alert system

- **MCP Server**: HTTP-based tool interface
  - RESTful endpoints
  - JSON request/response
  - Error handling

- **Event Bus**: Async communication
  - Pub/sub pattern
  - Event-driven architecture
  - Decoupled components

- **Storage**: DuckDB persistence
  - Tasks, proposals, decisions
  - Metrics time series
  - Context snapshots

### Routing Strategies

The system supports three routing strategies that are automatically determined based on task characteristics:

| Strategy | When Used | Agents | Use Case |
|----------|-----------|--------|----------|
| SOLO | Low complexity/risk | 1 | Simple tasks, documentation |
| CONSENSUS | High risk, critical | 2+ | Architecture, security |
| SEQUENTIAL | High complexity | 2+ | Planning, multi-phase |

**Note**: Agents create tasks using `create_simple_task` (SOLO) or `create_consensus_task` (CONSENSUS). The routing module can automatically upgrade tasks to SEQUENTIAL strategy based on complexity, risk, and task type analysis.

### Agent Directives

Agents automatically detect routing signals from natural language. For complete directive documentation, see [Agent Orchestrator Instructions](docs/AGENT_ORCHESTRATOR_INSTRUCTIONS.md#directive-reference).

## License

MIT
