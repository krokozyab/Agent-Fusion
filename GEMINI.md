# Gemini Agent Workspace Context

This document provides a comprehensive overview of the Agent Fusion project, designed to be used as a persistent context for Gemini and other AI agents.

## Project Overview

Agent Fusion is a local MCP (Model Context Protocol) stack that enables multiple AI coding agents to collaborate on software development tasks. It consists of two main modules:

*   **Task Orchestrator:** A multi-agent workflow engine that manages task queues, consensus-based decision-making, and provides a web dashboard for observability. It supports various routing strategies like SOLO, CONSENSUS, SEQUENTIAL, and PARALLEL.
*   **Context Addon:** A filesystem indexer that creates and maintains a rich project index with embeddings, enabling semantic search and context-aware agents.

The system is built with **Kotlin** on the **JVM**, using **Gradle** for dependency management and builds. It leverages **DuckDB** for data storage, **Ktor** for the HTTP server, and **Kotlin Coroutines** for asynchronous operations.

## Key Files

*   `README.md`: The main entry point for understanding the project, its features, and how to get started.
*   `build.gradle.kts`: The primary Gradle build script, defining dependencies, plugins, and build tasks.
*   `settings.gradle.kts`: Gradle settings, including project modules.
*   `fusionagent.toml`: The main configuration file for the entire Agent Fusion system. This is where agents, context indexing, and other services are configured.
*   `docs/AGENT_ORCHESTRATOR_INSTRUCTIONS.md`: A playbook for AI agents on how to interact with the Task Orchestrator, including tool usage and communication protocols.
*   `docs/CONVERSATION_HANDOFF_WORKFLOW.md`: Explains the conversation-based handoff workflow between agents.
*   `docs/MCP_TOOL_QUICK_REFERENCE.md`: A quick reference guide for the available MCP tools.
*   `docs/TASK_ROUTING_GUIDE.md`: Explains the task routing mechanism in the orchestrator.
*   `docs/fusionagent_config_docs.md`: Detailed documentation for all configuration options in `fusionagent.toml`.
*   `docs/INSTALL.md`: Detailed installation instructions.
*   `src/main/kotlin/com/orchestrator/Main.kt`: The main entry point of the application.

## Building and Running

The project is built and managed using the Gradle wrapper (`./gradlew`).

### Build Commands

*   **Clean and Build:**
    ```bash
    ./gradlew clean build
    ```
*   **Build without running tests:**
    ```bash
    ./gradlew build -x test
    ```
*   **Run all tests:**
    ```bash
    ./gradlew test
    ```
*   **Generate a fat JAR:**
    ```bash
    ./gradlew shadowJar
    ```

### Running the Server

*   **Run from source:**
    ```bash
    ./gradlew run
    ```
*   **Run from fat JAR:**
    ```bash
    java -jar build/libs/orchestrator-all.jar
    ```
*   **Run in development mode (with auto-reloading):**
    ```bash
    ./gradlew run --continuous
    ```

## Configuration

The Agent Fusion system is configured via the `fusionagent.toml` file. This file is organized into several sections:

*   `[orchestrator.server]`: Configures the MCP server.
*   `[web]`: Configures the web dashboard server.
*   `[agents]`: Defines the AI agents available to the orchestrator.
*   `[context]`: Configures the context indexing system, including file watching, indexing rules, embedding models, and query settings.
*   `[ignore]`: Specifies file patterns to be excluded from the context index.

A detailed example with all available options can be found in `fusionagent.toml.example` and documented in `docs/fusionagent_config_docs.md`.

## Collaboration Workflows

The system supports several collaboration workflows:

*   **SOLO:** For simple, low-risk tasks that can be handled by a single agent.
*   **CONSENSUS:** For critical, high-risk tasks that require input and agreement from multiple agents.
*   **SEQUENTIAL:** For complex, multi-phase tasks that require handoffs between agents with different specializations.
*   **PARALLEL:** For research or exploratory tasks where multiple agents can work simultaneously without coordination.

The `docs/CONVERSATION_HANDOFF_WORKFLOW.md` and `docs/SEQUENCE_DIAGRAMS.md` files provide detailed explanations of these workflows.

## Agent Protocol

Agents interacting with the orchestrator must follow the protocol defined in `docs/AGENT_ORCHESTRATOR_INSTRUCTIONS.md`. This protocol governs how agents create and manage tasks, submit proposals, and communicate with each other.

### Core Principles

*   Agents create tasks for collaborative work.
*   Agents submit proposals in response to tasks.
*   Agents can continue tasks started by other agents.
*   Critical tasks (high risk or complexity) require consensus.

### MCP Tools

A rich set of MCP (Model Context Protocol) tools are available for agents to interact with the orchestrator. These tools are used for:

*   **Task Creation:** `create_simple_task`, `create_consensus_task`, `assign_task`
*   **Task Inquiry:** `get_pending_tasks`, `get_task_status`, `continue_task`
*   **Task Contribution:** `respond_to_task`, `submit_input`
*   **Task Completion:** `complete_task`
*   **Context Querying:** `query_context`

A quick reference for these tools is available in `docs/MCP_TOOL_QUICK_REFERENCE.md`.

## Development Conventions

### Architecture

*   **Plugin Architecture:** Agents are discovered and loaded using the Java Service Provider Interface (SPI).
*   **Event-Driven:** Components communicate asynchronously through an `EventBus`.
*   **Coroutine-First:** All I/O operations are designed to be non-blocking using Kotlin Coroutines.
*   **Immutable Domain Models:** Domain objects are immutable `data class` instances.
*   **Raw JDBC:** The project uses raw JDBC with DuckDB for direct database control, avoiding ORM overhead.

### Code Style

The project follows the official [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html), with 4-space indentation and a maximum line length of 120 characters.

### Contributing

Contributions should follow the standard GitHub workflow:

1.  Create a feature branch.
2.  Make changes and add corresponding tests.
3.  Ensure all tests pass (`./gradlew test`).
4.  Submit a pull request.

### Adding a New Agent

To add a new agent, you need to:

1.  Implement the `Agent` interface.
2.  Create an `AgentFactory` for the new agent.
3.  Register the factory via SPI in `META-INF/services/com.orchestrator.core.AgentFactory`.
4.  Add the agent's configuration to `fusionagent.toml`.

## Additional Documentation

The `devdoc` directory contains a wealth of additional documentation, including:

*   **Architecture:** Detailed architecture documents for the Context Addon, Web Dashboard, and state machine.
*   **Implementation Plans:** Comprehensive implementation plans for various features.
*   **API Reference:** An API reference and a guide for MCP agents.
*   **Development and Setup:** Guides for setting up the development environment, integrating with Claude, and other development-related notes.
*   **Fixes and Summaries:** Summaries of fixes and other development activities.
