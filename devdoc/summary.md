# Project Summary: Agent Fusion Orchestrator

This document provides a comprehensive summary of the Agent Fusion project, a multi-agent orchestration system, based on the markdown documentation files.

## 1. Project Overview

**Agent Fusion** is a multi-agent orchestration system designed to enable bidirectional collaboration between various AI agents, including **Claude Code, Codex CLI, Amazon Q Developer, and Gemini Code Assist**. The core of the system is a central **MCP (Model Context Protocol) Orchestrator Server** that manages a shared context, a persistent task queue, and orchestrates agent interactions.

The primary goal is to leverage the specialized strengths of different AI agents to solve complex tasks through intelligent task routing and consensus-based decision-making, while optimizing for token usage and ensuring high-quality outcomes.

## 2. Core Concepts & Architecture

### Key Concepts
- **Multi-Agent Collaboration**: The system is built to coordinate multiple heterogeneous agents, allowing them to work together on tasks.
- **Conversation-Based Handoff**: Agents communicate asynchronously through the orchestrator. A user manually switches between agents to progress a task, with the orchestrator maintaining the full context across these handoffs.
- **Shared State Server**: The orchestrator acts as a central hub, maintaining the task registry, proposals, conversation history, and decisions in a persistent DuckDB database.

### Core Architectural Components
The system is built on a modular, event-driven architecture using Kotlin, Ktor, and DuckDB.
- **Orchestration Engine**: The central coordinator that manages workflows.
- **Agent Registry**: Discovers and manages agents using a **Service Provider Interface (SPI)**, making the system extensible. New agents can be added as plugins.
- **Routing Module**: Intelligently classifies tasks based on complexity, risk, and user directives to select the optimal execution strategy (e.g., SOLO, CONSENSUS).
- **Consensus Module**: Facilitates multi-agent decision-making through strategies like voting, reasoning quality analysis, or merging proposals.
- **Context Module**: A universal project context retrieval system that provides relevant code and documentation snippets to agents. It uses a multi-provider architecture (semantic, symbol, full-text search) to deliver accurate and diverse context.
- **MCP Server**: An HTTP-based server implementing the Model Context Protocol for agent communication.

## 3. Workflows and Task Routing

The system uses a three-tier routing system that balances user control with intelligent automation.

### Task Creation (Entry Points)
Agents or users initiate tasks via three main MCP tools:
1.  `create_simple_task`: For routine, low-risk tasks intended for a single agent (SOLO).
2.  `create_consensus_task`: For critical, high-risk tasks that require input from multiple agents (CONSENSUS).
3.  `assign_task`: To directly assign a task to a specific agent.

### Routing Strategies
The `RoutingModule` automatically selects one of four strategies:
- **SOLO**: A single agent handles the entire task.
- **CONSENSUS**: Multiple agents provide input, and a decision is made based on the collected proposals.
- **SEQUENTIAL**: A task is broken into phases, with handoffs between agents (e.g., one agent plans, another implements).
- **PARALLEL**: Multiple agents work on the same task simultaneously without coordination, and all results are aggregated.

### User Directives
Users can influence routing through natural language hints in their prompts, such as:
- **Force Consensus**: "get Codex's input", "need consensus".
- **Prevent Consensus**: "solo", "skip review", "emergency".
- **Assign to Agent**: "ask Claude to...", "have Codex...".

## 4. Universal Context Retrieval System

A key feature is the add-on context retrieval system designed to provide agents with relevant information from the project codebase and documentation, with a goal of reducing token usage by 80%.

- **Architecture**: It can run embedded within the orchestrator or as a standalone service. It uses a `context.toml` file for extensive configuration.
- **Indexing**: On first run, it performs a "bootstrap" process, scanning configured project paths, chunking files based on language-specific strategies (e.g., by function, class, or markdown heading), generating vector embeddings using a local sentence-transformer model (via ONNX), and storing them in DuckDB.
- **Search Process**: The system uses a hybrid search approach:
    1.  **Parallel Fan-out**: A query is sent to multiple providers (Semantic, Symbol, Full-text, Git History) simultaneously.
    2.  **Result Fusion (RRF)**: Results from all providers are merged into a single ranked list using Reciprocal Rank Fusion.
    3.  **Re-ranking (MMR)**: The list is re-ranked using Maximal Marginal Relevance (MMR) to ensure diversity and avoid redundancy.
- **CLI Tools**: A `contextd` CLI is provided for validating configuration, inspecting the index, and rebuilding the context database.

## 5. Development and Installation

### Setup
- **Prerequisites**: JDK 17+ is required.
- **Installation**: The project can be run from a pre-built distribution (`.zip`) or built from source using the included Gradle wrapper.
- **Configuration**:
    - `config/agents.toml`: To enable, disable, and configure connections for different AI agents.
    - `config/application.conf`: For server settings.
    - `config/context.toml`: For the context retrieval system.

### Agent Setup
To connect agents like Codex CLI or Claude Code, their local MCP client configuration must be updated to point to the orchestrator's server URL (e.g., `http://localhost:3000/mcp`).

## 6. Key MCP Tools for Agents

The `AGENT_ORCHESTRATOR_INSTRUCTIONS.md` file outlines the protocol agents must follow. Key tools include:
- **Task Creation**: `create_simple_task`, `create_consensus_task`, `assign_task`.
- **Task Discovery**: `get_pending_tasks` to find work assigned to an agent.
- **Responding**: `respond_to_task` (recommended) or the combination of `continue_task` and `submit_input` to provide analysis or a proposal for a task.
- **Completion**: `complete_task` to mark a task as finished (should only be called by the primary agent).
