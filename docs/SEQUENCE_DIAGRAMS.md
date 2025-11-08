# Agent Fusion - Sequence Diagrams

This document contains sequence diagrams illustrating various workflows in the Agent Fusion orchestration system.

## Table of Contents

1. [Simple Task Flow (SOLO)](#simple-task-flow-solo)
2. [Consensus Task Flow (Multi-Agent)](#consensus-task-flow-multi-agent)
3. [Sequential Task Flow](#sequential-task-flow)
4. [Parallel Task Flow](#parallel-task-flow)
5. [Task Handoff Between Agents](#task-handoff-between-agents)

---

## Simple Task Flow (SOLO)

**Scenario**: User asks Claude Code to implement a simple feature. Low complexity, low risk.

```mermaid
sequenceDiagram
    participant User
    participant Claude as Claude Code
    participant MCP as MCP Orchestrator
    participant DB as DuckDB Storage

    User->>Claude: "Implement user login validation"

    Note over Claude: Analyzes task<br/>Complexity: 3/10<br/>Risk: 2/10

    Claude->>MCP: create_simple_task(title, description, type=IMPLEMENTATION)
    MCP->>MCP: Route task (detects SOLO strategy)
    MCP->>DB: Store task
    DB-->>MCP: task-123
    MCP-->>Claude: Task created (task-123)

    Note over Claude: Executes task independently

    Claude->>Claude: Write validation code
    Claude->>MCP: submit_input(task-123, code + explanation)
    MCP->>DB: Store proposal

    Claude->>MCP: complete_task(task-123, resultSummary)
    MCP->>DB: Update task status = COMPLETED
    MCP-->>Claude: Task completed

    Claude-->>User: "I've implemented user login validation<br/>with email format and password strength checks"
```

**Key Points:**
- Single agent handles entire workflow
- No consensus or review needed
- Fast execution (< 1 minute typically)
- Used for 70% of tasks

---

## Consensus Task Flow (Multi-Agent)

**Scenario**: User asks for critical architecture decision requiring multiple perspectives.

```mermaid
sequenceDiagram
    participant User
    participant Claude as Claude Code
    participant MCP as MCP Orchestrator
    participant DB as DuckDB Storage
    participant Codex as Codex CLI

    User->>Claude: "Design authentication system (get Codex input)"

    Note over Claude: Detects directive:<br/>"get Codex input"<br/>Forces CONSENSUS

    Claude->>MCP: create_consensus_task(title, description, forceConsensus=true)
    MCP->>MCP: Route task → CONSENSUS
    MCP->>MCP: Select agents [Claude, Codex]
    MCP->>DB: Store task + assignments
    DB-->>MCP: task-500
    MCP-->>Claude: Task created (task-500, assigned: [Claude, Codex])

    Note over Claude: Analyzes and<br/>develops proposal

    Claude->>MCP: respond_to_task(task-500, proposalA)
    Note over MCP: ProposalA:<br/>REST API + JWT<br/>Confidence: 0.75
    MCP->>DB: Store proposal-A
    MCP-->>Claude: Proposal submitted, waiting for Codex

    Claude-->>User: "I've submitted my proposal for REST API + JWT.<br/>Waiting for Codex's input."

    User->>User: Switch to Codex CLI

    User->>Codex: "Check pending tasks"
    Codex->>MCP: get_pending_tasks(agent=codex)
    MCP->>DB: Query pending tasks
    DB-->>MCP: [task-500]
    MCP-->>Codex: task-500: "Design authentication system"

    Note over Codex: Reviews context<br/>and Claude's proposal

    Codex->>MCP: respond_to_task(task-500, proposalB)
    Note over MCP: ProposalB:<br/>OAuth2 + OIDC<br/>Confidence: 0.92
    MCP->>DB: Store proposal-B
    MCP->>MCP: Execute consensus strategies

    Note over MCP: Voting Strategy:<br/>Codex wins (0.92 > 0.75)<br/>Reasoning Quality:<br/>OAuth2 more robust

    MCP->>DB: Store decision (winner: proposal-B)
    MCP-->>Codex: Both proposals received, consensus reached

    Codex-->>User: "I've analyzed both approaches.<br/>My OAuth2 proposal was selected (higher confidence)."

    User->>User: Switch back to Claude

    User->>Claude: "Continue task-500"
    Claude->>MCP: continue_task(task-500)
    MCP->>DB: Load task + proposals + decision
    DB-->>MCP: Full context
    MCP-->>Claude: Task context with both proposals + decision

    Claude-->>User: "Consensus reached: OAuth2 + OIDC approach selected.<br/>Codex's proposal won based on higher confidence and robustness."
```

**Key Points:**
- Multiple agents submit independent proposals
- Orchestrator executes consensus strategies
- Winner selected based on confidence & reasoning quality
- Used for critical decisions (5-10% of tasks)
- Requires user to switch between agents

---

## Sequential Task Flow

**Scenario**: Complex multi-phase task requiring handoffs between agents.

```mermaid
sequenceDiagram
    participant User
    participant Claude as Claude Code
    participant MCP as MCP Orchestrator
    participant DB as DuckDB Storage
    participant Codex as Codex CLI

    User->>Claude: "Design and implement payment processing system"

    Note over Claude: High complexity: 8/10<br/>Moderate risk: 6/10<br/>Route: SEQUENTIAL

    Claude->>MCP: create_simple_task(title, description, type=ARCHITECTURE)
    MCP->>MCP: Auto-detects SEQUENTIAL strategy
    MCP->>DB: Store task (routing=SEQUENTIAL)
    DB-->>MCP: task-600

    Note over Claude: Phase 1: Architecture

    Claude->>Claude: Design system architecture
    Claude->>MCP: submit_input(task-600, architecture_doc)
    MCP->>DB: Store architecture proposal

    Claude->>MCP: assign_task(target=codex, title="Review architecture")
    MCP->>DB: Create review task-601
    Claude-->>User: "Architecture complete. Handing to Codex for review."

    User->>User: Switch to Codex

    User->>Codex: "Check pending"
    Codex->>MCP: get_pending_tasks()
    MCP-->>Codex: task-601: "Review architecture for task-600"

    Codex->>MCP: continue_task(task-601)
    MCP->>DB: Load architecture from task-600
    MCP-->>Codex: Architecture document

    Note over Codex: Reviews architecture

    Codex->>MCP: submit_input(task-601, "Approved with suggestions")
    Codex->>MCP: complete_task(task-601)

    Codex->>MCP: assign_task(target=claude, title="Implement based on architecture")
    MCP->>DB: Create implementation task-602
    Codex-->>User: "Review complete. Returning to Claude for implementation."

    User->>User: Switch to Claude

    User->>Claude: "Check pending"
    Claude->>MCP: get_pending_tasks()
    MCP-->>Claude: task-602: "Implement payment system"

    Claude->>MCP: continue_task(task-602)
    MCP-->>Claude: Architecture + review feedback

    Note over Claude: Phase 2: Implementation

    Claude->>Claude: Implement payment system
    Claude->>MCP: submit_input(task-602, implementation)
    Claude->>MCP: complete_task(task-602)

    Claude-->>User: "Payment system implemented per architecture."
```

**Key Points:**
- Tasks progress through phases with handoffs
- Each agent completes their phase before handoff
- Context preserved across transitions
- Used for complex projects (10-15% of tasks)

---

## Parallel Task Flow

**Scenario**: Research task requiring diverse perspectives without coordination.

```mermaid
sequenceDiagram
    participant User
    participant Claude as Claude Code
    participant MCP as MCP Orchestrator
    participant DB as DuckDB Storage
    participant Codex as Codex CLI

    User->>Claude: "Research API rate limiting strategies in parallel"

    Note over Claude: Detects "parallel"<br/>Task type: RESEARCH<br/>Risk: 3/10, Complexity: 6/10

    Claude->>MCP: create_simple_task(title, description, type=RESEARCH)
    MCP->>MCP: Route task → PARALLEL strategy
    MCP->>MCP: Select agents [Claude, Codex]
    MCP->>DB: Store task + assignments
    DB-->>MCP: task-700

    par Parallel Execution
        Note over MCP: Orchestrator executes<br/>both agents simultaneously

        MCP->>Claude: Execute research (async)
        Note over Claude: Researches token bucket<br/>& leaky bucket algorithms
        Claude->>Claude: Conduct independent research
        Claude->>MCP: Return research results

        and

        MCP->>Codex: Execute research (async)
        Note over Codex: Researches sliding window<br/>& fixed window approaches
        Codex->>Codex: Conduct independent research
        Codex->>MCP: Return research results
    end

    MCP->>MCP: Collect all results (no voting)
    Note over MCP: Both results preserved equally<br/>No winner selection

    MCP->>DB: Store both research outputs
    MCP->>MCP: Build aggregated output

    MCP-->>Claude: All results collected

    Claude-->>User: === Parallel Execution Results (2/2 successful) ===<br/><br/>--- Agent 1: Claude Code ---<br/>Execution Time: 118s | Tokens: 1,245<br/>[Token bucket & leaky bucket analysis...]<br/><br/>--- Agent 2: Codex CLI ---<br/>Execution Time: 124s | Tokens: 1,189<br/>[Sliding window & fixed window analysis...]
```

**Key Points:**
- Agents execute simultaneously (not sequentially)
- No coordination between agents during execution
- All results preserved without ranking
- Fast execution (parallel = ~2 min vs sequential = ~4 min)
- Used for research and exploration (5-10% of tasks)

---

## Task Handoff Between Agents

**Scenario**: Agent needs another agent's expertise mid-task.

```mermaid
sequenceDiagram
    participant User
    participant Claude as Claude Code
    participant MCP as MCP Orchestrator
    participant DB as DuckDB Storage
    participant Codex as Codex CLI

    User->>Claude: "Refactor authentication module"

    Claude->>MCP: create_simple_task(...)
    MCP->>DB: Store task-800
    MCP-->>Claude: Task created

    Note over Claude: Starts refactoring<br/>Encounters complex<br/>security concern

    Claude->>MCP: submit_input(task-800, "Partial refactor, need security review")
    Claude->>MCP: assign_task(target=codex, title="Security review for task-800")
    MCP->>DB: Create task-801 (review task)

    Claude-->>User: "I've made progress but need Codex to review<br/>the security implications. Switching to Codex."

    User->>User: Switch to Codex

    User->>Codex: "Check pending tasks"
    Codex->>MCP: get_pending_tasks()
    MCP->>DB: Query tasks
    DB-->>MCP: task-801
    MCP-->>Codex: task-801: "Security review for task-800"

    Codex->>MCP: continue_task(task-801)
    MCP->>DB: Load task-800 context + Claude's work
    MCP-->>Codex: Full context from task-800

    Note over Codex: Reviews security aspects

    Codex->>MCP: submit_input(task-801, "Security issues found: X, Y, Z")
    Codex->>MCP: complete_task(task-801)

    Codex->>MCP: assign_task(target=claude, title="Fix security issues in task-800")
    MCP->>DB: Create task-802

    Codex-->>User: "Security review complete. Found 3 issues.<br/>Returning to Claude to fix them."

    User->>User: Switch to Claude

    User->>Claude: "Check pending"
    Claude->>MCP: get_pending_tasks()
    MCP-->>Claude: task-802: "Fix security issues"

    Claude->>MCP: continue_task(task-802)
    MCP->>DB: Load Codex's security findings
    MCP-->>Claude: Security review feedback

    Note over Claude: Fixes security issues

    Claude->>MCP: submit_input(task-802, "Fixed all security issues")
    Claude->>MCP: complete_task(task-802)
    Claude->>MCP: complete_task(task-800)

    Claude-->>User: "All security issues fixed. Refactoring complete."
```

**Key Points:**
- Agents can request help mid-task
- Full context shared across handoffs
- Asynchronous collaboration supported
- Context preserved in database
- Common pattern for code reviews and expertise needs

---

## Legend

### Participant Types
- **User**: Human developer interacting with agents
- **Claude Code / Codex CLI**: AI agent clients
- **MCP Orchestrator**: Central coordination server
- **DuckDB Storage**: Persistent storage layer

### Task Statuses
- `PENDING`: Task created, waiting for agent
- `IN_PROGRESS`: Agent actively working
- `WAITING_INPUT`: Waiting for another agent's input
- `COMPLETED`: Task finished successfully
- `FAILED`: Task execution failed

### Routing Strategies
- **SOLO**: Single agent (70% of tasks)
- **CONSENSUS**: Multiple agents vote (5-10%)
- **SEQUENTIAL**: Phased handoffs (10-15%)
- **PARALLEL**: Simultaneous execution (5-10%)

---

## Additional Resources

- [Conversation Handoff Workflow](CONVERSATION_HANDOFF_WORKFLOW.md) - Detailed workflow documentation
- [Agent Orchestrator Instructions](AGENT_ORCHESTRATOR_INSTRUCTIONS.md) - Agent configuration guide
- [Orchestrator Workflow Guide](../orchestrator_workflow_guide.md) - Human user perspective

---

**Generated**: 2025-10-11
**Version**: 1.0
