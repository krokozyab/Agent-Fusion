# Agent Fusion

Agent Fusion is a local MCP (Model Context Protocol) stack that lets multiple coding agents collaborate while sharing a rich project index. It is composed of two flagship modules:

- **Task Orchestrator** – multi-agent workflow engine, consensus router, and web dashboard.
- **Context Addon** – live filesystem indexer with embeddings, search providers, and context tools.

🎥 **[Watch the demo](https://youtu.be/kXkTh0fJ0Lc)** to see consensus collaboration in action.

---

## Quick Links

| Resource | Purpose |
|----------|---------|
| [Installation](docs/INSTALL.md) | Set up the orchestrator, context addon, and agent clients |
| [Agent playbook](docs/AGENT_ORCHESTRATOR_INSTRUCTIONS.md) | Share with Claude/Codex/Gemini/Q before they connect |
| [Task Orchestrator README](docs/README_TASK_ORCHESTRATOR.md) | Deep dive on routing, consensus, UI, and APIs |
| [Context Addon README](docs/README_CONTEXT_ADDON.md) | Indexing pipeline, MCP tools, dashboard, troubleshooting |
| [API reference](docs/API_REFERENCE.md) | HTTP + MCP endpoints |
| [Development guide](docs/DEVELOPMENT.md) | Build, test, contribution workflow |

---

## Module Overview

### Task Orchestrator
The control plane for multi-agent work. It manages task queues, consensus voting, routing policies (solo/consensus/sequential/parallel), and surfaces activity through the `/tasks`, `/files`, and `/index` dashboards. Read the dedicated guide here → [docs/README_TASK_ORCHESTRATOR.md](docs/README_TASK_ORCHESTRATOR.md).

### Context Addon
Ingests project files, keeps DuckDB-based embeddings in sync, and exposes retrieval tools (semantic, symbol, raw text) to every agent. It also powers the `/index` status page with filesystem reconciliations and rebuild controls. Details live here → [docs/README_CONTEXT_ADDON.md](docs/README_CONTEXT_ADDON.md).

---

## Getting Started

1. **Install & configure**  
   Follow [docs/INSTALL.md](docs/INSTALL.md) to build the server and point your agents at the MCP endpoint. Configure watch roots and indexing limits in `fusionagent.toml`.

2. **Prime your agents**  
   In their first session, tell each agent:  
   ```text
   Read and follow the instructions in docs/AGENT_ORCHESTRATOR_INSTRUCTIONS.md
   ```
   That equips them with routing intents, tool protocols, and handoff etiquette.

3. **Run the orchestrator**  
   ```bash
   ./gradlew run
   ```  
   - Web UI: `http://localhost:8081`  
   - MCP endpoint: configured in `fusionagent.toml`

4. **Connect agents & collaborate**  
   Agents can now create tasks, join consensus rounds, and query the shared index. Watch `/tasks` for live proposals and `/index` for indexing health.

---

## Documentation Map

| Topic | Key References |
|-------|----------------|
| Architecture & sequences | [WEB_DASHBOARD_ARCHITECTURE.md](docs/WEB_DASHBOARD_ARCHITECTURE.md), [SEQUENCE_DIAGRAMS.md](docs/SEQUENCE_DIAGRAMS.md) |
| Task routing & decision making | [TASK_ROUTING_GUIDE.md](docs/TASK_ROUTING_GUIDE.md), [STATE_MACHINE.md](docs/STATE_MACHINE.md) |
| Consensus workflows | [IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md), [FUNCTIONAL_REQUIREMENTS.md](docs/FUNCTIONAL_REQUIREMENTS.md) |
| Context indexing | [CONTEXT_ADDON_ARCHITECTURE.md](docs/CONTEXT_ADDON_ARCHITECTURE.md), [CONTEXT_IMPLEMENTATION_PLAN.md](docs/CONTEXT_IMPLEMENTATION_PLAN.md) |
| MCP tools & usage | [MCP_TOOL_QUICK_REFERENCE.md](docs/MCP_TOOL_QUICK_REFERENCE.md), [API_REFERENCE.md](docs/API_REFERENCE.md) |
| Deployment & operations | [DEPLOYMENT_NOTES.md](docs/DEPLOYMENT_NOTES.md), [ONNX_MODEL_SETUP.md](docs/ONNX_MODEL_SETUP.md) |

---

## Why Agent Fusion?

- **True bidirectional collaboration** – any agent can open tasks, route work, or trigger consensus.
- **Rich project context** – fast, multi-root indexing with embeddings, chunking, and filesystem reconciliation.
- **Evidence-driven decisions** – proposals, votes, and final decisions are persisted with full audit history.
- **Event-driven architecture** – async event bus feeds dashboards, SSE progress streams, and external integrations.

Agent Fusion makes local multi-agent workflows practical: plug in your favorite coding LLMs, give them a shared brain, and keep everything observable from one unified UI.

The system supports four routing strategies that are automatically determined based on task characteristics:

| Strategy | When Used | Agents | Use Case |
|----------|-----------|--------|----------|
| SOLO | Low complexity/risk | 1 | Simple tasks, documentation |
| CONSENSUS | High risk, critical | 2+ | Architecture, security decisions |
| SEQUENTIAL | High complexity | 2+ | Planning, multi-phase projects |
| PARALLEL | Research/testing, divisible tasks | 2+ | Code generation, data analysis |

**Note**: Agents create tasks using `create_simple_task` (SOLO) or `create_consensus_task` (CONSENSUS). The routing module can automatically select SEQUENTIAL or PARALLEL strategies based on complexity, risk, task type, and natural language signals (e.g., "parallel", "concurrent").

### Agent Directives

Agents automatically detect routing signals from natural language. For complete directive documentation, see [Agent Orchestrator Instructions](docs/AGENT_ORCHESTRATOR_INSTRUCTIONS.md#directive-reference).

## License

MIT
