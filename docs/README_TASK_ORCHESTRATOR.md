# Task Orchestrator

The Task Orchestrator is the multi-agent control plane that coordinates Claude Code, Codex CLI, Amazon Q Developer, and Gemini Code Assist via the Model Context Protocol (MCP). It owns task routing, consensus workflows, shared context, and the web dashboard used to monitor live collaboration.

## Capabilities
- **Intelligent Routing** – automatically chooses between solo execution, sequential handoffs, parallel work, or multi-agent consensus based on task complexity/risk.
- **Consensus Engine** – collects proposals, runs democratic voting, and records the final decision with full audit history.
- **Persistent Task Queue** – agents poll pending work, so collaboration survives restarts and agent swaps.
- **Web Dashboard** – `/index` shows index health, `/tasks` tracks live assignments, `/files` gives indexed-file visibility.
- **Event Bus** – async pub/sub for task updates, index progress, metrics, and alerts.

## Key Docs
- [Architecture & Sequences](WEB_DASHBOARD_ARCHITECTURE.md)
- [Implementation Plan](WEB_DASHBOARD_IMPLEMENTATION_PLAN.md)
- [Task Routing Guide](TASK_ROUTING_GUIDE.md)
- [State Machine](STATE_MACHINE.md)
- [MCP API Reference](API_REFERENCE.md)

## Core Services
| Area | Files |
|------|-------|
| Routing & workflows | `src/main/kotlin/com/orchestrator/modules/routing` |
| Task lifecycle & storage | `src/main/kotlin/com/orchestrator/modules/tasks` |
| Event bus | `src/main/kotlin/com/orchestrator/modules/events` |
| Web UI | `src/main/kotlin/com/orchestrator/web` |
| MCP tooling | `src/main/kotlin/com/orchestrator/mcp/tools` |

## Runbook
1. `./gradlew run` – starts the orchestrator (web on `localhost:8081`, MCP on configured port).
2. Point local agents at the MCP URL (see [INSTALL.md](../docs/INSTALL.md)).
3. Visit `/tasks` to watch proposals and consensus voting in real time.
4. Use `/index` to rebuild/refresh the context index when code changes.

## Observability
- Logs: `logs/orchestrator.log`
- Metrics & alerts (WIP): see `src/main/kotlin/com/orchestrator/modules/metrics`
- SSE progress endpoints: `/sse/tasks`, `/sse/index`

## Contributing
- Development setup: [DEVELOPMENT.md](DEVELOPMENT.md)
- Agent instructions to share with LLMs: [AGENT_ORCHESTRATOR_INSTRUCTIONS.md](AGENT_ORCHESTRATOR_INSTRUCTIONS.md)

