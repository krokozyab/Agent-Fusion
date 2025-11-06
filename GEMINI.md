# Gemini Agent Workspace Context

This document provides a comprehensive overview of the Agent Fusion project, designed to be used as a persistent context for Gemini and other AI agents.

## Project Overview

Agent Fusion is a local MCP (Model Context Protocol) stack that enables multiple AI coding agents to collaborate on software development tasks. It consists of two main modules:

*   **Task Orchestrator:** A multi-agent workflow engine that manages task queues, consensus-based decision-making, and provides a web dashboard for observability.
*   **Context Addon:** A filesystem indexer that creates and maintains a rich project index with embeddings, enabling semantic search and context-aware agents.

The system is built with **Kotlin** on the **JVM**, using **Gradle** for dependency management and builds. It leverages **DuckDB** for data storage, **Ktor** for the HTTP server, and **Kotlin Coroutines** for asynchronous operations.

## Key Files

*   `README.md`: The main entry point for understanding the project, its features, and how to get started.
*   `build.gradle.kts`: The primary Gradle build script, defining dependencies, plugins, and build tasks.
*   `settings.gradle.kts`: Gradle settings, including project modules.
*   `fusionagent.toml`: The main configuration file for the entire Agent Fusion system. This is where agents, context indexing, and other services are configured.
*   `docs/DEVELOPMENT.md`: The developer's guide, containing detailed information about the project structure, architecture, and development workflows.
*   `docs/AGENT_ORCHESTRATOR_INSTRUCTIONS.md`: A playbook for AI agents on how to interact with the Task Orchestrator, including tool usage and communication protocols.
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

*   `[agents]`: Defines the AI agents available to the orchestrator, including their type, name, and model parameters.
*   `[context]`: Configures the context indexing system, including file watching, indexing rules, embedding models, and query settings.
*   `[ignore]`: Specifies file patterns to be excluded from the context index.

A detailed example with all available options can be found in `fusionagent.toml.example`.

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
