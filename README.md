# Claude-Codex Orchestrator

A dual-agent orchestration system that enables Claude Code and Codex to collaborate bidirectionally through intelligent task routing, consensus-based decision making.

## Architecture Overview

### Core Concept

The system enables two AI agents (Claude Code and Codex CLI) to collaborate on complex tasks through a central MCP (Model Context Protocol) server that maintains shared context and orchestrates their interactions.

```
┌─────────────────┐                                    ┌─────────────────┐
│                 │                                    │                 │
│  Claude Code    │◄──────────────────────────────────►│   Codex CLI     │
│   (Agent 1)     │        Bidirectional               │   (Agent 2)     │
│                 │        Communication               │                 │
└────────┬────────┘                                    └────────┬────────┘
         │                                                      │
         │  MCP Client                                MCP Client│
         │  Connection                                Connection│
         │                                                      │
         ▼                                                      ▼
    ┌────────────────────────────────────────────────────────────────┐
    │                                                                │
    │                   MCP Orchestrator Server                      │
    │                                                                │
    │  ┌──────────────────────────────────────────────────────┐      │
    │  │          Shared Context & Task Queue                 │      │
    │  │  • Task routing & assignment                         │      │
    │  │  • Proposals & consensus voting                      │      │
    │  │  • Conversation history                              │      │
    │  └──────────────────────────────────────────────────────┘      │
    │                                                                │
    │  ┌──────────────────────────────────────────────────────┐      │
    │  │           Persistent Storage                         │      │
    │  │  • Tasks, Proposals, Decisions                       │      │
    │  │  • Context snapshots                                 │      │
    │  └──────────────────────────────────────────────────────┘      │
    │                                                                │
    └────────────────────────────────────────────────────────────────┘
```

### How It Works

1. **Agents Connect**: Both Claude Code and Codex CLI connect to the MCP server as clients
2. **Task Creation**: Either agent can create tasks (simple, consensus, or assigned)
3. **Context Sharing**: The server maintains shared context visible to both agents
4. **Collaboration**: Agents submit proposals, vote on solutions, and review each other's work
5. **Routing**: Intelligent routing decides whether tasks need single-agent or multi-agent collaboration
6. **Persistence**: All interactions, decisions, and metrics are stored in DuckDB

**Key Benefits:**
- **Bidirectional**: Both agents can initiate tasks and respond to each other
- **Context Preservation**: Full conversation history and task context maintained centrally
- **Flexible Workflows**: Supports solo, consensus, sequential, and parallel execution modes
- **Audit Trail**: Complete decision history with rationale and proposals

## Features

🎥 **[Watch Demo Video](https://youtu.be/kXkTh0fJ0Lc)** - See consensus collaboration in action

- **Intelligent Routing**: Automatically routes tasks to the best agent based on complexity, risk, and capabilities
- **Consensus Mode**: Multiple agents collaborate on critical decisions with voting strategies
- **User Directives**: Control routing with force consensus, agent assignment, and emergency bypass
- **MCP Server**: Model Context Protocol server for tool-based interactions
- **Event-Driven**: Async event bus for component communication

## Installation

**No API keys required** - This system runs entirely locally using your existing Claude Code and Codex CLI installations.

See [Installation Guide](docs/INSTALL.md) for setup instructions.

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

| Strategy | When Used | Agents | Use Case |
|----------|-----------|--------|----------|
| SOLO | Low complexity/risk | 1 | Simple tasks, documentation |
| CONSENSUS | High risk, critical | 2-3 | Architecture, security |
| SEQUENTIAL | High complexity | 2-3 | Planning, multi-phase |
| PARALLEL | Research, testing | 2-3 | Exploration, validation |

### Agent Directives

Agents automatically detect routing signals from natural language. For complete directive documentation, see [Agent Orchestrator Instructions](docs/AGENT_ORCHESTRATOR_INSTRUCTIONS.md#directive-reference).

## License

MIT
